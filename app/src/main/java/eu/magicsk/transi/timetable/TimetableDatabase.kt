package eu.magicsk.transi.timetable

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.MAGIC_API_URL
import eu.magicsk.transi.data.remote.responses.TimetableDatabaseInfo
import eu.magicsk.transi.data.remote.responses.idsbk.Departure
import eu.magicsk.transi.data.remote.responses.idsbk.DirectionDeparture
import eu.magicsk.transi.data.remote.responses.idsbk.Route
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDirection
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.util.Prefs
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface TimetableDownloadState {
    data object Idle : TimetableDownloadState
    data object Checking : TimetableDownloadState
    data class Downloading(val percent: Int?) : TimetableDownloadState
    data object Decompressing : TimetableDownloadState
    data object Ready : TimetableDownloadState
    data class Error(@StringRes val message: Int) : TimetableDownloadState
}

/**
 * The offline timetable database: schedule.db with base.db attached as `base`, downloaded from the manifest
 * on the Magic API. Port of the iOS `TimetableDatabase` (download, open, delete and the timetable queries).
 */
@Singleton
class TimetableDatabase @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: DataRepository,
    @Named("magic") private val client: OkHttpClient,
) {
    private enum class DbFile(val fileName: String, val shaKey: String) {
        BASE("base.db", Prefs.TIMETABLE_BASE_DB_SHA),
        SCHEDULE("schedule.db", Prefs.TIMETABLE_SCHEDULE_DB_SHA),
    }

    private val mutableState = MutableStateFlow<TimetableDownloadState>(TimetableDownloadState.Idle)
    val state: StateFlow<TimetableDownloadState> = mutableState.asStateFlow()

    private val prefs = Prefs.get(context)
    private val dir = File(context.filesDir, "timetable_db")
    private var job: Job? = null

    // An unexpected failure (say a manifest missing a field) ends as "Download failed" instead of a crash.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ ->
        mutableState.value = TimetableDownloadState.Error(R.string.timetable_db_download_failed)
    })

    @Volatile
    private var db: SQLiteDatabase? = null

    val isReady: Boolean get() = db != null
    val isOfflineEnabled: Boolean get() = prefs.getBoolean(Prefs.OFFLINE_TIMETABLES, false)

    /** App start: with offline timetables on, open the installed databases and check for updates. */
    @MainThread
    fun start() = serially { if (isOfflineEnabled) openAndUpdate() }

    /** Starts again after a failed download or update check; a running one is left alone. */
    @MainThread
    fun retryIfFailed() {
        if (isOfflineEnabled && !isReady && state.value is TimetableDownloadState.Error) start()
    }

    @MainThread
    fun setOfflineEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(Prefs.OFFLINE_TIMETABLES, enabled) }
        serially { if (enabled) openAndUpdate() else close() }
    }

    /** Closes and removes the databases, download leftovers and installed versions; turns offline timetables off. */
    @MainThread
    fun delete() {
        prefs.edit { putBoolean(Prefs.OFFLINE_TIMETABLES, false) }
        serially {
            close()
            dir.deleteRecursively()
            prefs.edit { DbFile.entries.forEach { remove(it.shaKey) } }
        }
    }

    /** The space the downloaded files take, in bytes. Reads the disk: call it off the main thread. */
    @WorkerThread
    fun sizeOnDisk(): Long = dir.walk().filter { it.isFile }.sumOf { it.length() }

    /**
     * Runs [block] on the schedule connection (base.db attached as `base`) off the main thread.
     * Null when the database is not open or the query fails.
     */
    suspend fun <T> read(block: (SQLiteDatabase) -> T): T? = withContext(Dispatchers.IO) {
        val db = db ?: return@withContext null
        try {
            block(db)
        } catch (_: SQLiteException) {
            null
        } catch (_: IllegalStateException) {
            // The connection was closed by a delete or an update while the query ran.
            null
        }
    }

    // Each operation cancels the previous one (a running download) and waits for it before starting.
    private fun serially(block: suspend () -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            block()
        }
    }

    private suspend fun openAndUpdate() {
        open()
        checkAndUpdate()
    }

    private fun open() {
        if (db != null) {
            mutableState.value = TimetableDownloadState.Ready
            return
        }
        val base = File(dir, DbFile.BASE.fileName)
        val schedule = File(dir, DbFile.SCHEDULE.fileName)
        if (!base.exists() || !schedule.exists()) return
        var opened: SQLiteDatabase? = null
        try {
            opened = SQLiteDatabase.openDatabase(schedule.path, null, SQLiteDatabase.OPEN_READWRITE)
            opened.execSQL("ATTACH DATABASE ? AS base", arrayOf(base.path))
            // As on iOS: leave WAL so no -wal/-shm files are needed next to the downloaded databases.
            opened.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            db = opened
            mutableState.value = TimetableDownloadState.Ready
        } catch (_: SQLiteException) {
            opened?.close()
            close()
        }
    }

    private fun close() {
        val open = db
        db = null
        open?.close()
        mutableState.value = TimetableDownloadState.Idle
    }

    private suspend fun checkAndUpdate() {
        mutableState.value = TimetableDownloadState.Checking
        val manifest = repository.getTimetableManifest().data
        if (manifest == null) {
            mutableState.value = if (isReady) {
                TimetableDownloadState.Ready
            } else {
                TimetableDownloadState.Error(R.string.timetable_db_check_failed)
            }
            return
        }
        // ponytail: planner.db is skipped; iOS downloads it for the offline planner but never queries it.
        // A missing file is downloaded again too; iOS would wait in "checking" forever.
        val needed = listOf(DbFile.BASE to manifest.databases.base, DbFile.SCHEDULE to manifest.databases.schedule)
            .filter { (file, info) ->
                info.sha256 != prefs.getString(file.shaKey, "") || !File(dir, file.fileName).exists()
            }
        if (needed.isEmpty()) {
            open()
            return
        }

        close()
        val total = needed.sumOf { it.second.size_bytes_gz }
        var received = 0L
        mutableState.value = TimetableDownloadState.Downloading(if (total > 0) 0 else null)
        try {
            dir.mkdirs()
            needed.forEach { (file, info) ->
                download(file, info) { bytes ->
                    received += bytes
                    if (total > 0) {
                        val percent = (received * 100 / total).coerceAtMost(100).toInt()
                        mutableState.value = TimetableDownloadState.Downloading(percent)
                    }
                }
            }
        } catch (_: IOException) {
            mutableState.value = TimetableDownloadState.Error(R.string.timetable_db_download_failed)
            return
        }
        open()
    }

    private suspend fun download(file: DbFile, info: TimetableDatabaseInfo, onBytes: (Int) -> Unit) {
        val gz = File(dir, "${file.fileName}.gz.part")
        try {
            // Relative URLs are relative to the Magic API; absolute ones are used as they are.
            val url = MAGIC_API_URL.toHttpUrl().resolve(info.url) ?: throw IOException("Invalid URL")
            // ponytail: cancellation is checked between chunks, so a stalled read ends at the OkHttp read timeout.
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code != 200) throw IOException("HTTP ${response.code}")
                response.body.byteStream().use { input ->
                    gz.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            onBytes(read)
                        }
                    }
                }
            }
            mutableState.value = TimetableDownloadState.Decompressing
            installGzip(gz, info.sha256, File(dir, file.fileName))
            prefs.edit { putString(file.shaKey, info.sha256) }
        } finally {
            gz.delete()
        }
    }

    // region Timetable queries (TimetableDatabase.swift 257-576)

    /** Routes deduplicated by short name, keeping the variant with the most trips; regional buses get type 700. Unordered. */
    suspend fun queryRoutes(): List<Route>? = read { db ->
        db.select(
            """
            SELECT r.rowid, r.route_short_name, r.route_long_name,
                CASE WHEN r.feed = 'regional' AND r.route_type = 3 THEN 700 ELSE r.route_type END as route_type,
                r.route_color, r.route_text_color
            FROM base.routes r
            WHERE r.rowid = (
                SELECT br.rowid
                FROM base.routes br
                LEFT JOIN trips t ON br.route_id = t.route_id AND br.feed = t.feed
                WHERE br.route_short_name = r.route_short_name
                GROUP BY br.rowid
                ORDER BY COUNT(t.trip_id) DESC
                LIMIT 1
            )
            """
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val rowid = c.int("rowid") ?: continue
                    val shortName = c.string("route_short_name") ?: continue
                    val routeType = c.int("route_type") ?: continue
                    add(
                        Route(
                            route_id = rowid,
                            short_name = shortName,
                            long_name = c.string("route_long_name").orEmpty(),
                            description = null,
                            route_type = routeType,
                            color = c.string("route_color"),
                            text_color = c.string("route_text_color"),
                            graphics = null,
                            notes = emptyList(),
                        )
                    )
                }
            }
        }
    }

    /**
     * Directions across every route sharing the short name: by direction_id with its most common headsign,
     * or, when no trip has a direction_id, one per headsign with the synthetic id -(rank by trip count).
     */
    suspend fun queryDirections(shortName: String): List<TimetableDirection>? = read { db ->
        var hasRealDirectionIds = false
        val rows = mutableListOf<Triple<Int?, String, Int>>()
        for (route in db.routeVariants(shortName)) {
            db.select(
                """
                SELECT direction_id, trip_headsign, COUNT(*) as cnt
                FROM trips WHERE route_id = ? AND feed = ?
                GROUP BY direction_id, trip_headsign
                ORDER BY direction_id, cnt DESC
                """,
                route.routeId, route.feed,
            ).use { c ->
                while (c.moveToNext()) {
                    val directionId = c.int("direction_id")
                    if (directionId != null) hasRealDirectionIds = true
                    val headsign = c.string("trip_headsign") ?: continue
                    val count = c.int("cnt") ?: continue
                    rows += Triple(directionId, headsign, count)
                }
            }
        }
        if (hasRealDirectionIds) {
            val headsignCounts = sortedMapOf<Int, MutableMap<String, Int>>()
            rows.forEach { (directionId, headsign, count) ->
                headsignCounts.getOrPut(directionId ?: 0) { linkedMapOf() }.merge(headsign, count, Int::plus)
            }
            headsignCounts.map { (directionId, counts) -> TimetableDirection(directionId, counts.maxBy { it.value }.key) }
        } else {
            val headsignCounts = linkedMapOf<String, Int>()
            rows.forEach { (_, headsign, count) -> headsignCounts.merge(headsign, count, Int::plus) }
            headsignCounts.entries.sortedByDescending { it.value }
                .mapIndexed { index, entry -> TimetableDirection(-(index + 1), entry.key) }
        }
    }

    /** The day's trips in a direction, each with its ordered stops; times in minutes after the service day's midnight. */
    suspend fun queryDepartures(shortName: String, directionId: Int, directionName: String, date: LocalDate): List<Departure>? =
        read { db ->
            val routes = db.routeVariants(shortName)
            val routeType = routes.firstOrNull()?.routeType ?: return@read emptyList()
            val departures = mutableListOf<Departure>()
            for (route in routes) {
                val services = db.activeServiceIds(route.feed, date)
                if (services.isEmpty()) continue
                for ((tripId, headsign) in db.trips(route, directionId, directionName, services)) {
                    if (headsign == null) continue
                    val stopTimes = db.select(
                        """
                        SELECT st.departure_time, st.stop_id, bs.stop_name
                        FROM stop_times st
                        LEFT JOIN base.stops bs ON st.stop_id = bs.stop_id AND st.feed = bs.feed
                        WHERE st.trip_id = ? AND st.feed = ?
                        ORDER BY st.stop_sequence
                        """,
                        tripId, route.feed,
                    ).use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                add(Triple(c.int("departure_time"), c.string("stop_id"), c.string("stop_name")))
                            }
                        }
                    }
                    val firstDeparture = stopTimes.firstOrNull()?.first ?: continue
                    val stops = stopTimes.mapIndexedNotNull { index, (departure, stopId, stopName) ->
                        if (departure == null || stopName == null) {
                            null
                        } else {
                            DirectionDeparture(index, stopName, departure / 60, null, stopId)
                        }
                    }
                    departures += Departure(
                        trip_id = departures.size,
                        trip_flags = 0,
                        trip_headsign = headsign,
                        departure = firstDeparture / 60,
                        route_type = routeType,
                        external_id = tripId,
                        direction_departures = stops,
                        trip_delay = null,
                    )
                }
            }
            departures.sortedBy { it.departure }
        }

    private class RouteVariant(val routeId: String, val feed: String, val routeType: Int)

    // Keyed by short name, not rowid: a rowid in a screen's arguments may name another line after an update.
    private fun SQLiteDatabase.routeVariants(shortName: String): List<RouteVariant> =
        select("SELECT route_id, feed, route_type FROM base.routes WHERE route_short_name = ?", shortName)
            .use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val routeId = c.string("route_id") ?: continue
                        val feed = c.string("feed") ?: continue
                        val routeType = c.int("route_type") ?: continue
                        add(RouteVariant(routeId, feed, routeType))
                    }
                }
            }

    /** (trip_id, trip_headsign) of the route's trips in the direction (by headsign for synthetic ids) running on one of [services]. */
    private fun SQLiteDatabase.trips(
        route: RouteVariant,
        directionId: Int,
        directionName: String,
        services: Set<String>,
    ): List<Pair<String, String?>> {
        // ponytail: services are matched here rather than in `IN (?, …)`; SQLite before Android 11 allows only 999 parameters.
        val cursor = if (directionId < 0) {
            select(
                "SELECT trip_id, trip_headsign, service_id FROM trips WHERE route_id = ? AND feed = ? AND trip_headsign = ?",
                route.routeId, route.feed, directionName,
            )
        } else {
            select(
                "SELECT trip_id, trip_headsign, service_id FROM trips WHERE route_id = ? AND feed = ? AND COALESCE(direction_id, 0) = ?",
                route.routeId, route.feed, directionId,
            )
        }
        return cursor.use { c ->
            buildList {
                while (c.moveToNext()) {
                    if (c.string("service_id") !in services) continue
                    val tripId = c.string("trip_id") ?: continue
                    add(tripId to c.string("trip_headsign"))
                }
            }
        }
    }

    // endregion

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** Service ids of [feed] running on [date]; for queries run inside [TimetableDatabase.read]. */
internal fun SQLiteDatabase.activeServiceIds(feed: String, date: LocalDate): Set<String> {
    val day = date.serviceDate()
    // The weekday column (`monday` … `sunday`) comes from a fixed set of names, never from user input.
    val weekday = date.dayOfWeek.name.lowercase(Locale.ROOT)
    val calendar = select(
        "SELECT service_id FROM calendar WHERE feed = ? AND start_date <= ? AND end_date >= ? AND $weekday = 1",
        feed, day, day,
    ).use { c -> buildList { while (c.moveToNext()) c.string("service_id")?.let(::add) } }
    val exceptions = select(
        "SELECT service_id, exception_type FROM calendar_dates WHERE feed = ? AND date = ?",
        feed, day,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                val serviceId = c.string("service_id") ?: continue
                val type = c.int("exception_type") ?: continue
                add(serviceId to type)
            }
        }
    }
    return activeServiceIds(calendar, exceptions)
}

/** Query with typed bind arguments ([String], [Int] or [Long]); `rawQuery` would bind everything as text. */
fun SQLiteDatabase.select(sql: String, vararg args: Any): Cursor =
    rawQueryWithFactory({ _, driver, editTable, query ->
        args.forEachIndexed { index, arg ->
            when (arg) {
                is String -> query.bindString(index + 1, arg)
                is Int -> query.bindLong(index + 1, arg.toLong())
                is Long -> query.bindLong(index + 1, arg)
                else -> throw IllegalArgumentException("Unsupported SQL argument type ${arg.javaClass.name}")
            }
        }
        SQLiteCursor(driver, editTable, query)
    }, sql, null, "") // The edit table only matters for the long-removed cursor commit API.

/** Null unless the column holds an integer (like Swift's `as? Int`). */
fun Cursor.int(column: String): Int? =
    getColumnIndexOrThrow(column).let { if (getType(it) == Cursor.FIELD_TYPE_INTEGER) getInt(it) else null }

/** Null unless the column holds text (like Swift's `as? String`). */
fun Cursor.string(column: String): String? =
    getColumnIndexOrThrow(column).let { if (getType(it) == Cursor.FIELD_TYPE_STRING) getString(it) else null }

/** `calendar` services of the day, plus `calendar_dates` additions (type 1), minus removals (type 2), in row order. */
fun activeServiceIds(calendar: List<String>, exceptions: List<Pair<String, Int>>): Set<String> =
    calendar.toMutableSet().apply {
        exceptions.forEach { (serviceId, type) ->
            when (type) {
                1 -> add(serviceId)
                2 -> remove(serviceId)
            }
        }
    }

/**
 * Verifies the SHA-256 (lowercase hex) of the gzip file [gz], then inflates it into [destination] through a temporary
 * file and an atomic rename, so a failure never leaves a partial database behind.
 */
fun installGzip(gz: File, expectedSha256: String, destination: File) {
    val sha256 = HashingSink.sha256(blackholeSink())
    gz.source().buffer().use { it.readAll(sha256) }
    if (sha256.hash.hex() != expectedSha256) throw IOException("SHA-256 mismatch")
    val part = File(destination.path + ".part")
    try {
        GZIPInputStream(gz.inputStream().buffered()).use { input -> part.outputStream().use { input.copyTo(it) } }
        if (!part.renameTo(destination)) throw IOException("Cannot replace ${destination.name}")
    } finally {
        part.delete()
    }
}
