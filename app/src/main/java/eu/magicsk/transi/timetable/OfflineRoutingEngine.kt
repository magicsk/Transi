package eu.magicsk.transi.timetable

import android.database.sqlite.SQLiteDatabase
import eu.magicsk.transi.data.models.ArrivalDeparture
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.PartStop
import eu.magicsk.transi.data.models.ROUTE_TYPE_WALK
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
     * Up to 5 journeys between the stops named [fromName] and [toName], soonest first: leaving at or after [date],
     * or with [ArrivalDeparture.ARRIVAL] the latest ones arriving by it. Empty when none is found; null when the
     * database is not open.
     */
    suspend fun plan(
        fromName: String,
        toName: String,
        date: Instant,
        arrivalDeparture: ArrivalDeparture,
        maxTransfers: Int,
        maxWalkMinutes: Int,
    ): List<Journey>? {
        val (day, time) = serviceTime(date)
        val (stops, stopIndex) = database.read(::loadStops) ?: return null
        val origins = stopsNamed(stops, fromName)
        val destinations = stopsNamed(stops, toName)
        if (origins.isEmpty() || destinations.isEmpty()) return emptyList()
        val arrival = arrivalDeparture == ArrivalDeparture.ARRIVAL
        val maxLegs = (maxTransfers + 1).coerceIn(1, MAX_LEGS)
        val context = currentCoroutineContext()
        var journeys = emptyList<Journey>()
        for (window in WINDOWS) {
            val from = if (arrival) time - window else time
            val trips = database.read { db -> loadTrips(db, stopIndex, day, from, from + window, context) } ?: return null
            journeys = withContext(Dispatchers.Default) {
                searchJourneys(stops, trips, origins, destinations, time, arrival, maxLegs, walkTransfers = maxWalkMinutes >= 1)
                    .map { journey(it, stops, trips, day) }
            }
            if (journeys.size >= WANTED_JOURNEYS) break
        }
        return journeys.distinct().sortedBy { it.parts.first().startDeparture }
    }

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
                for (service in database.runningServiceIds(db, feed, date)) {
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

/** Every platform of the station named [name], else of the stations with a stop name containing it. */
internal fun stopsNamed(stops: List<OfflineStop>, name: String): Set<Int> {
    if (name.isBlank()) return emptySet()
    val station = stationName(name)
    val stations = stops.filter { it.station == station }
        .ifEmpty { stops.filter { it.name.contains(name, ignoreCase = true) } }
        .mapTo(HashSet()) { it.station }
    return stops.indices.filterTo(HashSet()) { stops[it].station in stations }
}

internal class OfflineStop(val name: String, val platform: String?, val zone: String?, val gps: StopGps?) {
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
    if (!arrival) return OfflineRouter(stops, trips).search(origins, destinations, time, maxLegs, walkTransfers)
    val reversed = reversedInTime(trips)
    return OfflineRouter(stops, reversed).search(destinations, origins, -time, maxLegs, walkTransfers)
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

/** The iOS search over trips in memory; stops and trips are referred to by their list index. */
internal class OfflineRouter(private val stops: List<OfflineStop>, private val trips: List<OfflineTrip>) {
    private class Call(val trip: Int, val position: Int, val time: Int)

    /** A stop reached by [ride] (after [walk] to its boarding platform) from [parent]; the root has neither. */
    private class Label(val parent: Label?, val walk: Leg.Walk?, val ride: Leg.Ride?, val stop: Int, val time: Int)

    /** Trips calling at each stop, by time. */
    private val calls: List<List<Call>> = List(stops.size) { ArrayList<Call>() }.also { calls ->
        trips.forEachIndexed { index, trip ->
            trip.stops.forEachIndexed { position, stop -> calls[stop] += Call(index, position, trip.times[position]) }
        }
        calls.forEach { it.sortBy(Call::time) }
    }

    private val platforms: Map<String, List<Int>> = stops.indices.groupBy { stops[it].station }

    /**
     * [CANDIDATES] departures from [origins] at or after [start], soonest first, each get their earliest-arriving journey
     * to [destinations]; up to [WANTED_JOURNEYS] that no other one beats are returned in candidate order.
     */
    suspend fun search(
        origins: Set<Int>,
        destinations: Set<Int>,
        start: Int,
        maxLegs: Int,
        walkTransfers: Boolean,
    ): List<List<Leg>> {
        val candidates = origins
            .flatMap { stop -> calls[stop].drop(firstAtOrAfter(stop, start)).map { stop to it.time } }
            .distinct()
            .sortedBy { it.second }
        val isDestination = BooleanArray(stops.size).also { destinations.forEach { stop -> it[stop] = true } }
        val best = IntArray(stops.size)
        val journeys = ArrayList<List<Leg>>()
        // Unlike iOS, departures from a stop until the journey found from it leaves are skipped: they find it again.
        val leaves = HashMap<Int, Int>()
        var searched = 0
        for ((stop, time) in candidates) {
            if (time <= (leaves[stop] ?: Int.MIN_VALUE)) continue
            if (searched++ == CANDIDATES) break
            val found = earliestArrival(stop, time, isDestination, maxLegs, walkTransfers, best) ?: continue
            leaves[stop] = found.filterIsInstance<Leg.Ride>().departure
            // Unlike iOS, a journey leaving no later, arriving no sooner and riding no less than another is dropped
            // (repeats too), so a bus ride to catch the same tram does not crowd out boarding it at the next platform.
            if (journeys.any { beats(it, found) }) continue
            journeys.removeAll { beats(found, it) }
            journeys += found
            if (journeys.size >= WANTED_JOURNEYS) break
        }
        return journeys
    }

    private fun beats(a: List<Leg>, b: List<Leg>): Boolean {
        val rides = a.filterIsInstance<Leg.Ride>()
        val other = b.filterIsInstance<Leg.Ride>()
        return rides.departure >= other.departure && rides.arrival <= other.arrival && rides.size <= other.size
    }

    private val List<Leg.Ride>.departure get() = first().let { trips[it.trip].times[it.board] }
    private val List<Leg.Ride>.arrival get() = last().let { trips[it.trip].times[it.alight] }

    /**
     * Breadth first by rides: every trip leaving the reached stops within [MAX_WAIT] (after a ride also from the
     * station's other platforms, a minute later) improves the best arrival at its later stops. Stops at the first
     * ride count that reaches a destination and returns the earliest arrival there.
     */
    private suspend fun earliestArrival(
        start: Int,
        time: Int,
        isDestination: BooleanArray,
        maxLegs: Int,
        walkTransfers: Boolean,
        best: IntArray,
    ): List<Leg>? {
        best.fill(Int.MAX_VALUE)
        var labels = listOf(Label(null, null, null, start, time))
        val arrivals = ArrayList<Label>()
        for (leg in 1..maxLegs) {
            // One label per stop: a later improvement always arrives earlier, so it replaces the earlier one.
            val next = LinkedHashMap<Int, Label>()
            for (label in labels) {
                currentCoroutineContext().ensureActive()
                val lastTrip = label.ride?.trip ?: -1
                val walks = if (label.ride != null && walkTransfers) {
                    platforms.getValue(stops[label.stop].station).filter { it != label.stop }
                        .map { Leg.Walk(label.stop, it, label.time) }
                } else {
                    emptyList()
                }
                for (walk in listOf(null) + walks) {
                    val boardStop = walk?.to ?: label.stop
                    val boardTime = if (walk == null) label.time else label.time + PLATFORM_WALK_SECONDS
                    val stopCalls = calls[boardStop]
                    for (index in firstAtOrAfter(boardStop, boardTime) until stopCalls.size) {
                        val call = stopCalls[index]
                        if (call.time > boardTime + MAX_WAIT) break
                        if (call.trip == lastTrip) continue
                        val trip = trips[call.trip]
                        for (position in call.position + 1 until trip.stops.size) {
                            val stop = trip.stops[position]
                            val arrival = trip.times[position]
                            if (arrival >= best[stop]) continue
                            best[stop] = arrival
                            val reached = Label(label, walk, Leg.Ride(call.trip, call.position, position), stop, arrival)
                            if (isDestination[stop]) arrivals += reached else if (leg < maxLegs) next[stop] = reached
                        }
                    }
                }
            }
            if (arrivals.isNotEmpty() || next.isEmpty()) break
            labels = next.values.toList()
        }
        val arrived = arrivals.minByOrNull { it.time } ?: return null
        return generateSequence(arrived) { it.parent }.toList().asReversed().flatMap { listOfNotNull(it.walk, it.ride) }
    }

    /** Index of the first call at [stop] at or after [time]. */
    private fun firstAtOrAfter(stop: Int, time: Int): Int =
        // The comparison never says "equal", so binarySearch returns -(insertion point) - 1.
        -(calls[stop].binarySearch { if (it.time < time) -1 else 1 } + 1)
}
