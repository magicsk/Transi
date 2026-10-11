package eu.magicsk.transi.timetable

import android.database.sqlite.SQLiteDatabase
import eu.magicsk.transi.data.models.ArrivalDeparture
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.PartStop
import eu.magicsk.transi.data.models.ROUTE_TYPE_WALK
import eu.magicsk.transi.data.models.distance
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** Ids of offline journeys, which page like neither online source. */
const val OFFLINE_JOURNEY_PREFIX = "o-"

private const val PLATFORM_WALK_SECONDS = 60
private const val MINUTE = 60
private const val DAY = 86_400
private const val HOUR = 3_600

/** Searched ahead (or back, for arrivals) first, then up to a day when that finds too few journeys. */
private val WINDOWS = listOf(4 * HOUR, 24 * HOUR)

// ponytail: a service day's GTFS times are assumed to end by 30:00 (this feed ends at 29:05); later ones are not loaded.
private const val LAST_SERVICE_SECOND = 30 * HOUR
private const val MAX_LEGS = 6
private const val CANDIDATES = 30
private const val WANTED_JOURNEYS = 5
private const val MAX_WAIT = 12 * HOUR

// Same-named stops this close are platforms of one station (the widest real one, Americké nám., spans 416 m);
// farther ones are stops of another town, like Rovinka, Majerská 7.5 km from Majerská.
private const val STATION_RADIUS_METERS = 500.0

private const val BUS_STATION = "Autobusová stanica"
private val TOWN_PREFIXES = listOf("Bratislava, ", "Rovinka, ", "Dunajská Lužná, ", "Miloslavov, ")

private const val TRIPS_SQL = """
    SELECT t.rowid AS trip, r.route_short_name AS line, r.route_type AS type, t.trip_headsign AS headsign
    FROM trips t JOIN base.routes r ON r.route_id = t.route_id AND r.feed = t.feed
    WHERE t.service_id = ? AND t.feed = ?
"""

private const val STOP_TIMES_SQL = """
    SELECT t.rowid AS trip, s.rowid AS stop, st.departure_time AS time,
        (st.pickup_type = 3 OR st.drop_off_type = 3) AS request
    FROM trips t
    JOIN stop_times st ON st.trip_id = t.trip_id AND st.feed = t.feed
    JOIN base.stops s ON s.stop_id = st.stop_id AND s.feed = st.feed
    WHERE t.service_id = ? AND t.feed = ? AND st.departure_time BETWEEN ? AND ?
    ORDER BY t.rowid, st.stop_sequence
"""

/**
 * The offline trip planner over the downloaded timetables: a port of the iOS `OfflineRoutingEngine`
 * (TimetableDatabase.swift). stop_times count seconds from midnight of the service day on Bratislava clocks.
 */
class OfflineRoutingEngine @Inject constructor(private val database: TimetableDatabase) {
    val isReady: Boolean get() = database.isReady

    /**
     * About 5 journeys between the stops [from] and [to], soonest first: leaving at or after [date], or with
     * [ArrivalDeparture.ARRIVAL] the latest ones arriving by it. Empty when none is found; null when the database
     * is not open or has no stop for [from] or [to] (see [stopsNamed]), for the online planner to answer.
     */
    suspend fun plan(
        from: Stop,
        to: Stop,
        date: Instant,
        arrivalDeparture: ArrivalDeparture,
        maxTransfers: Int,
        maxWalkMinutes: Int,
    ): List<Journey>? {
        val (day, time) = serviceTime(date)
        val arrival = arrivalDeparture == ArrivalDeparture.ARRIVAL
        val maxLegs = (maxTransfers + 1).coerceIn(1, MAX_LEGS)
        val context = currentCoroutineContext()
        var journeys = emptyList<Journey>()
        for (window in WINDOWS) {
            val start = if (arrival) time - window else time
            // Stops and trips come from one read: a database replaced in between would mismatch their indexes.
            val loaded = database.read { db ->
                val (stops, stopIndex) = loadStops(db)
                val origins = stopsNamed(stops, from.name, StopGps(lon = from.lng, lat = from.lat))
                val destinations = stopsNamed(stops, to.name, StopGps(lon = to.lng, lat = to.lat))
                val named = origins.isNotEmpty() && destinations.isNotEmpty()
                val trips = if (named) loadTrips(db, stopIndex, day, start, start + window, context) else emptyList()
                Loaded(stops, origins, destinations, trips)
            } ?: return null
            if (loaded.origins.isEmpty() || loaded.destinations.isEmpty()) return null
            journeys = withContext(Dispatchers.Default) {
                val (stops, origins, destinations, trips) = loaded
                searchJourneys(stops, trips, origins, destinations, time, arrival, maxLegs, walkTransfers = maxWalkMinutes >= 1)
                    .map { journey(it, stops, trips, day) }
                    .distinctBy { it.sameRidesKey }
            }
            if (journeys.size >= WANTED_JOURNEYS) break
        }
        return journeys.sortedBy { it.parts.first().startDeparture }
    }

    private data class Loaded(
        val stops: List<OfflineStop>,
        val origins: Set<Int>,
        val destinations: Set<Int>,
        val trips: List<OfflineTrip>,
    )

    /** Every stop, and the list index of each stop rowid (-1 for none). */
    private fun loadStops(db: SQLiteDatabase): Pair<List<OfflineStop>, IntArray> {
        val rowids = ArrayList<Int>()
        val stops = ArrayList<OfflineStop>()
        db.select("SELECT rowid, stop_name, platform_code, zone_id, stop_lat, stop_lon FROM base.stops").use { c ->
            while (c.moveToNext()) {
                rowids += c.getInt(0)
                stops += OfflineStop(
                    name = c.getString(1),
                    platform = c.string("platform_code")?.takeIf { it.isNotBlank() },
                    // "-" marks stops outside the fare zones.
                    zone = c.string("zone_id")?.takeIf { it.isNotBlank() && it != "-" },
                    gps = StopGps(lon = c.getDouble(5), lat = c.getDouble(4)),
                )
            }
        }
        val index = IntArray((rowids.maxOrNull() ?: -1) + 1) { -1 }
        rowids.forEachIndexed { stop, rowid -> index[rowid] = stop }
        return stops to index
    }

    /**
     * The trips running between [from] and [to] (seconds after midnight of [day]), cut to their stops in that window.
     * A trip of the previous or next service day has its times shifted by a day, so night trips connect.
     */
    private fun loadTrips(
        db: SQLiteDatabase,
        stopIndex: IntArray,
        day: LocalDate,
        from: Int,
        to: Int,
        context: CoroutineContext,
    ): List<OfflineTrip> {
        val feeds = db.select("SELECT feed FROM feed_info").use { c ->
            buildList { while (c.moveToNext()) c.string("feed")?.let(::add) }
        }
        val trips = ArrayList<OfflineTrip>()
        // Service days whose times (0 … LAST_SERVICE_SECOND, shifted by a day each) overlap the window.
        for (offset in -Math.floorDiv(LAST_SERVICE_SECOND - from, DAY)..Math.floorDiv(to, DAY)) {
            val shift = offset * DAY
            val date = day.plusDays(offset.toLong())
            for (feed in feeds) {
                for (service in db.activeServiceIds(feed, date)) {
                    context.ensureActive()
                    val lines = HashMap<Int, TripLine>()
                    db.select(TRIPS_SQL, service, feed).use { c ->
                        while (c.moveToNext()) {
                            val trip = c.int("trip") ?: continue
                            val type = c.int("type") ?: continue
                            lines[trip] = TripLine(c.string("line").orEmpty(), type, c.string("headsign"))
                        }
                    }
                    db.select(STOP_TIMES_SQL, service, feed, from - shift, to - shift).use { c ->
                        val count = c.count
                        val tripIds = IntArray(count)
                        val stops = IntArray(count)
                        val times = IntArray(count)
                        val requests = BooleanArray(count)
                        for (row in 0 until count) {
                            c.moveToPosition(row)
                            tripIds[row] = c.getInt(0)
                            stops[row] = stopIndex[c.getInt(1)]
                            times[row] = c.getInt(2) + shift
                            requests[row] = c.getInt(3) == 1
                        }
                        // Rows come trip by trip, each in stop order.
                        var first = 0
                        for (row in 1..count) {
                            if (row < count && tripIds[row] == tripIds[first]) continue
                            lines[tripIds[first]]?.let { line ->
                                trips += OfflineTrip(
                                    line.name, line.routeType, line.headsign, stops.copyOfRange(first, row),
                                    times.copyOfRange(first, row), requests.copyOfRange(first, row),
                                )
                            }
                            first = row
                        }
                    }
                }
            }
        }
        return trips
    }

    private class TripLine(val name: String, val routeType: Int, val headsign: String?)

    private fun journey(legs: List<Leg>, stops: List<OfflineStop>, trips: List<OfflineTrip>, day: LocalDate): Journey {
        val zones = sortedSetOf<String>()
        val parts = legs.map { leg ->
            when (leg) {
                is Leg.Walk -> {
                    val from = stops[leg.from]
                    val to = stops[leg.to]
                    Part(
                        startStopName = from.name,
                        endStopName = to.name,
                        startStopCode = from.platform,
                        endStopCode = to.platform,
                        startDeparture = wallClock(day, leg.departure),
                        endArrival = wallClock(day, leg.departure + PLATFORM_WALK_SECONDS),
                        routeType = ROUTE_TYPE_WALK,
                        tripHeadsign = null,
                        routeShortName = null,
                        startStopGps = from.gps,
                        endStopGps = to.gps,
                    )
                }
                is Leg.Ride -> {
                    val trip = trips[leg.trip]
                    val calls = (leg.board..leg.alight).map { position ->
                        val stop = stops[trip.stops[position]]
                        stop.zone?.let(zones::add)
                        PartStop(
                            name = stop.name,
                            platform = stop.platform,
                            time = wallClock(day, trip.times[position]),
                            gps = stop.gps,
                            zone = stop.zone,
                            isRequestStop = trip.requestStops[position],
                            stopId = null,
                        )
                    }
                    val board = calls.first()
                    val alight = calls.last()
                    Part(
                        startStopName = board.name,
                        endStopName = alight.name,
                        startStopCode = board.platform,
                        endStopCode = alight.platform,
                        startDeparture = board.time,
                        endArrival = alight.time,
                        routeType = trip.routeType,
                        tripHeadsign = trip.headsign,
                        routeShortName = trip.line,
                        startStopGps = board.gps,
                        endStopGps = alight.gps,
                        stops = calls,
                    )
                }
            }
        }
        return Journey(OFFLINE_JOURNEY_PREFIX + UUID.randomUUID(), parts, zones.toList())
    }
}

/** The service day and second of it of [time]: stop_times count from the day's midnight on Bratislava clocks. */
internal fun serviceTime(time: Instant): Pair<LocalDate, Int> =
    time.atZone(BRATISLAVA).let { it.toLocalDate() to it.toLocalTime().toSecondOfDay() }

/** The instant [seconds] after midnight of [day] on Bratislava clocks (negative or past a day too). */
internal fun wallClock(day: LocalDate, seconds: Int): Instant =
    day.atStartOfDay().plusSeconds(seconds.toLong()).atZone(BRATISLAVA).toInstant()

/** The station of a stop name, whose platforms connect by a minute's walk: without the town, "AS" as the bus station. */
internal fun stationName(stopName: String): String {
    val name = TOWN_PREFIXES.fold(stopName) { name, prefix -> name.replace(prefix, "") }
    return if (name == "AS") BUS_STATION else name
}

/**
 * Every platform of the station named [name]: its stops near those of exactly that name (another town's stop of the
 * same station name is not one). A name of no station (the timetables have no Suché mýto, and write Gajary, NAFTA)
 * takes the stops within [STATION_RADIUS_METERS] of [place], where the stop was picked: those whose name contains it,
 * else all of them. Empty when there are none.
 */
internal fun stopsNamed(stops: List<OfflineStop>, name: String, place: StopGps): Set<Int> {
    if (name.isBlank()) return emptySet()
    val station = stationName(name)
    val platforms = stops.indices.filter { stops[it].station == station }
    // ponytail: a name matching no stop exactly takes every platform of its station, which could span two towns;
    // no stop name of the live feed does that.
    val named = platforms.filter { stops[it].name.equals(name, ignoreCase = true) }.ifEmpty { platforms }
    return platforms.filter { platform -> named.any { isNear(stops[it].gps, stops[platform].gps) } }
        .ifEmpty {
            val around = stops.indices.filter { isNear(stops[it].gps, place) }
            around.filter { stops[it].name.contains(name, ignoreCase = true) }.ifEmpty { around }
        }
        .toSet()
}

/** Whether [a] and [b] are within [STATION_RADIUS_METERS]. */
internal fun isNear(a: StopGps, b: StopGps): Boolean = a.distance(b) <= STATION_RADIUS_METERS

internal class OfflineStop(val name: String, val platform: String?, val zone: String?, val gps: StopGps) {
    val station = stationName(name)
}

/** A run of a line: its stops (indexes) in order with their times in seconds after midnight of the search day. */
internal class OfflineTrip(
    val line: String,
    val routeType: Int,
    val headsign: String?,
    val stops: IntArray,
    val times: IntArray,
    val requestStops: BooleanArray = BooleanArray(stops.size),
)

internal sealed interface Leg {
    /** On [trip] from its [board]th to its [alight]th stop. */
    data class Ride(val trip: Int, val board: Int, val alight: Int) : Leg

    /** A minute's walk between two platforms of a station. */
    data class Walk(val from: Int, val to: Int, val departure: Int) : Leg
}

/**
 * Journeys leaving [origins] at or after [time]; with [arrival], the latest ones reaching [destinations] by [time],
 * found by the same search over the timetable reversed in time.
 */
internal suspend fun searchJourneys(
    stops: List<OfflineStop>,
    trips: List<OfflineTrip>,
    origins: Set<Int>,
    destinations: Set<Int>,
    time: Int,
    arrival: Boolean,
    maxLegs: Int,
    walkTransfers: Boolean,
): List<List<Leg>> {
    if (!arrival) {
        return OfflineRouter(stops, trips, maxLegs, walkTransfers, reversed = false).search(origins, destinations, time)
    }
    val reversed = reversedInTime(trips)
    return OfflineRouter(stops, reversed, maxLegs, walkTransfers, reversed = true).search(destinations, origins, -time)
        .map { legs -> legs.forwardInTime(reversed) }
}

/** [trips] backwards: stops reversed, times negated, so an earliest arrival on them is a latest departure. */
internal fun reversedInTime(trips: List<OfflineTrip>): List<OfflineTrip> = trips.map { trip ->
    OfflineTrip(
        trip.line, trip.routeType, trip.headsign, trip.stops.reversedArray(),
        IntArray(trip.times.size) { -trip.times[trip.times.lastIndex - it] }, trip.requestStops.reversedArray(),
    )
}

/** Legs found on [reversedInTime] trips, forwards in time. */
private fun List<Leg>.forwardInTime(trips: List<OfflineTrip>): List<Leg> = asReversed().map { leg ->
    when (leg) {
        is Leg.Ride -> trips[leg.trip].stops.lastIndex.let { last -> Leg.Ride(leg.trip, last - leg.alight, last - leg.board) }
        is Leg.Walk -> Leg.Walk(leg.to, leg.from, -leg.departure - PLATFORM_WALK_SECONDS)
    }
}

/**
 * The iOS search over trips in memory, up to [maxLegs] rides changing (with [walkTransfers]) to another platform of
 * the station; stops and trips are referred to by their list index. [reversed] when the trips are [reversedInTime].
 */
internal class OfflineRouter(
    private val stops: List<OfflineStop>,
    private val trips: List<OfflineTrip>,
    private val maxLegs: Int,
    private val walkTransfers: Boolean,
    private val reversed: Boolean,
) {
    private class Call(val trip: Int, val position: Int, val time: Int)

    /** A stop reached by [ride] (after [walk] to its boarding platform) from [parent]; the root has neither. */
    private class Label(val parent: Label?, val walk: Leg.Walk?, val ride: Leg.Ride?, val stop: Int, val time: Int) {
        fun legs(): List<Leg> =
            generateSequence(this) { it.parent }.toList().asReversed().flatMap { listOfNotNull(it.walk, it.ride) }
    }

    /** Trips calling at each stop, by time. */
    private val calls: List<List<Call>> = List(stops.size) { ArrayList<Call>() }.also { calls ->
        trips.forEachIndexed { index, trip ->
            trip.stops.forEachIndexed { position, stop -> calls[stop] += Call(index, position, trip.times[position]) }
        }
        calls.forEach { it.sortBy(Call::time) }
    }

    /** The other platforms of each stop's station: its stops near this one. */
    private val platforms: List<List<Int>> = List(stops.size) { ArrayList<Int>() }.also { platforms ->
        stops.indices.groupBy { stops[it].station }.values.forEach { station ->
            for (a in station) for (b in station) if (a != b && isNear(stops[a].gps, stops[b].gps)) platforms[a] += b
        }
    }

    /**
     * [CANDIDATES] departures from [origins] at or after [start], soonest first, each get their fastest journey to
     * [destinations] for each number of rides; about [WANTED_JOURNEYS] that no other one beats are returned in
     * candidate order.
     */
    suspend fun search(origins: Set<Int>, destinations: Set<Int>, start: Int): List<List<Leg>> {
        val isDestination = BooleanArray(stops.size).also { destinations.forEach { stop -> it[stop] = true } }
        // Nothing leaves a destination: the stops around Suché mýto include Hodžovo nám.
        val candidates = origins.filterNot { isDestination[it] }
            .flatMap { stop -> calls[stop].drop(firstAtOrAfter(stop, start)).map { stop to it.time } }
            .distinct()
            .sortedBy { it.second }
        val best = IntArray(stops.size)
        val journeys = ArrayList<List<Leg>>()
        // Unlike iOS, departures from a stop until the first journey found from it leaves are skipped: they find
        // the same journeys again.
        val leaves = HashMap<Int, Int>()
        var searched = 0
        for ((stop, time) in candidates) {
            if (time <= (leaves[stop] ?: Int.MIN_VALUE)) continue
            if (searched++ == CANDIDATES) break
            val found = fastestJourneys(stop, time, isDestination, best)
            if (found.isEmpty()) continue
            leaves[stop] = found.minOf { it.filterIsInstance<Leg.Ride>().departure }
            for (journey in found) {
                // Unlike iOS, a journey leaving no later, arriving no sooner (to the shown minute) and riding no less
                // than another is dropped (repeats too), so a bus ride to catch the same tram does not crowd out
                // boarding it at the next platform.
                if (journeys.any { beats(it, journey) }) continue
                journeys.removeAll { beats(journey, it) }
                journeys += journey
            }
            if (journeys.size >= WANTED_JOURNEYS) break
        }
        return journeys
    }

    private fun beats(a: List<Leg>, b: List<Leg>): Boolean {
        val rides = a.filterIsInstance<Leg.Ride>()
        val other = b.filterIsInstance<Leg.Ride>()
        return minute(rides.departure) >= minute(other.departure) && minute(rides.arrival) <= minute(other.arrival) &&
            rides.size <= other.size
    }

    /** The minute [time] shows as, still ordered as the search's times: [reversed] ones are negated, so round up. */
    private fun minute(time: Int): Int = if (reversed) -Math.floorDiv(-time, MINUTE) else Math.floorDiv(time, MINUTE)

    private val List<Leg.Ride>.departure get() = first().let { trips[it.trip].times[it.board] }
    private val List<Leg.Ride>.arrival get() = last().let { trips[it.trip].times[it.alight] }

    /**
     * Breadth first by rides: every trip leaving the reached stops within [MAX_WAIT] (after a ride also from the
     * station's other platforms, a minute later) improves the best arrival at its later stops; a ride ending at the
     * station it started from is none. Each number of rides that reaches a destination at least a minute sooner than
     * the last journey added adds that journey, fewest rides first; nothing reached at or after the soonest arrival
     * yet is followed further.
     */
    private suspend fun fastestJourneys(start: Int, time: Int, isDestination: BooleanArray, best: IntArray): List<List<Leg>> {
        best.fill(Int.MAX_VALUE)
        var labels = listOf(Label(null, null, null, start, time))
        var soonest = Int.MAX_VALUE
        var added = Int.MAX_VALUE
        val journeys = ArrayList<List<Leg>>()
        for (leg in 1..maxLegs) {
            // One label per stop: a later improvement always arrives earlier, so it replaces the earlier one.
            val next = LinkedHashMap<Int, Label>()
            var arrived: Label? = null
            for (label in labels) {
                currentCoroutineContext().ensureActive()
                if (label.time >= soonest) continue
                val lastTrip = label.ride?.trip ?: -1
                val walks = if (label.ride != null && walkTransfers) {
                    platforms[label.stop].map { Leg.Walk(label.stop, it, label.time) }
                } else {
                    emptyList()
                }
                for (walk in listOf(null) + walks) {
                    val boardStop = walk?.to ?: label.stop
                    val boardTime = if (walk == null) label.time else label.time + PLATFORM_WALK_SECONDS
                    val stopCalls = calls[boardStop]
                    for (index in firstAtOrAfter(boardStop, boardTime) until stopCalls.size) {
                        val call = stopCalls[index]
                        if (call.time > boardTime + MAX_WAIT || call.time >= soonest) break
                        if (call.trip == lastTrip) continue
                        val trip = trips[call.trip]
                        for (position in call.position + 1 until trip.stops.size) {
                            val arrival = trip.times[position]
                            if (arrival >= soonest) break
                            val stop = trip.stops[position]
                            if (arrival >= best[stop]) continue
                            // No ride within a station, like 69 from one Avion Shop. Park platform to another.
                            if (stop == boardStop || stop in platforms[boardStop]) continue
                            best[stop] = arrival
                            val reached = Label(label, walk, Leg.Ride(call.trip, call.position, position), stop, arrival)
                            if (isDestination[stop]) {
                                soonest = arrival
                                arrived = reached
                            } else if (leg < maxLegs) {
                                next[stop] = reached
                            }
                        }
                    }
                }
            }
            // A minute sooner at least: seconds sooner would look like the same journey with more rides.
            arrived?.takeIf { it.time <= added - MINUTE }?.let {
                journeys += it.legs()
                added = it.time
            }
            if (next.isEmpty()) break
            labels = next.values.toList()
        }
        return journeys
    }

    /** Index of the first call at [stop] at or after [time]. */
    private fun firstAtOrAfter(stop: Int, time: Int): Int =
        // The comparison never says "equal", so binarySearch returns -(insertion point) - 1.
        -(calls[stop].binarySearch { if (it.time < time) -1 else 1 } + 1)
}
