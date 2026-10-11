package eu.magicsk.transi.data.models

import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** One row of the journey detail's step list (iOS JourneyStep). */
sealed interface JourneyStep {
    /** A transit leg, by part index. */
    data class Ride(val index: Int) : JourneyStep

    /** Walk parts by index, then the ride they lead to; null when the walk ends the journey. */
    data class Walk(val parts: List<Int>, val to: Int?) : JourneyStep

    /** A change between two rides with no walk part, into the ride at this index. */
    data class Change(val to: Int) : JourneyStep
}

/** The rides, the walks before them, and the changes that need no walk (iOS `Journey.steps`). */
val Journey.steps: List<JourneyStep>
    get() {
        val steps = mutableListOf<JourneyStep>()
        var walks = mutableListOf<Int>()
        var hasRidden = false
        parts.forEachIndexed { index, part ->
            if (part.isWalk) {
                walks += index
                return@forEachIndexed
            }
            if (walks.isNotEmpty()) {
                steps += JourneyStep.Walk(walks, index)
            } else if (hasRidden) {
                steps += JourneyStep.Change(index)
            }
            steps += JourneyStep.Ride(index)
            walks = mutableListOf()
            hasRidden = true
        }
        if (walks.isNotEmpty()) steps += JourneyStep.Walk(walks, null)
        return steps
    }

val Journey.rideCount get() = parts.count { !it.isWalk }

val Journey.walkMinutes get() = parts.filter { it.isWalk }.sumOf { it.minutes }

/** The fare zones of the known stops, in travel order; empty until a leg knows its stops. */
val Journey.zoneNames: List<String> get() = parts.flatMap { it.stops.orEmpty() }.mapNotNull { it.zone }.distinct()

val Part.minutes get() = minutesBetween(startDeparture, endArrival)

/**
 * Not arrived yet, and departing within the hour or already departed: while its vehicle's live departure and delay
 * are this leg's. A known delay (seconds) moves the end to the expected arrival.
 */
fun Part.isLive(now: Instant, delaySeconds: Int?): Boolean =
    endArrival.plusSeconds(max(delaySeconds ?: 0, 0).toLong()) > now && startDeparture < now.plusSeconds(3600)

/** The leg's stops, or its boarding and alighting stops while the rest are unknown. */
val Part.legStops: List<PartStop>
    get() = stops?.takeIf { it.size > 1 } ?: listOf(
        PartStop(startStopName.orEmpty(), startStopCode, startDeparture, startStopGps, null, false, null),
        PartStop(endStopName.orEmpty(), endStopCode, endArrival, endStopGps, null, false, null),
    )

/** iOS `progress(at:delays:).phase == .arrived`: every vehicle and the walk after the last one are over. */
fun Journey.hasArrived(now: Instant, delays: Map<Int, Int>): Boolean {
    fun end(index: Int, delayOf: Int?) = parts[index].endArrival.plusSeconds((delayOf?.let(delays::get) ?: 0).toLong())
    val transit = parts.indices.filter { !parts[it].isWalk }
    if (transit.any { now < end(it, it) }) return false
    val last = parts.lastIndex
    return last < 0 || last == transit.lastOrNull() || now >= end(last, transit.lastOrNull())
}

/** Spare minutes left at a change after the walk, rounded down; negative once the connection is gone. */
data class TransferBuffer(val minutes: Int) {
    val isTight get() = minutes <= 1
    val isLikelyMissed get() = minutes < 0
}

/** The whole minutes a delay in seconds is shown as, rounded half away from zero: 50 s late is "+1 min", 20 s "on time". */
fun delayMinutes(seconds: Int): Int {
    val minutes = Math.round(abs(seconds) / 60.0).toInt()
    return if (seconds < 0) -minutes else minutes
}

/** This scheduled time on a vehicle [delaySeconds] late (early when negative), moved by the minutes the delay reads as. */
fun Instant.expected(delaySeconds: Int?): Instant = plusSeconds(delayMinutes(delaySeconds ?: 0) * 60L)

/**
 * The spare time at each change between transit legs, keyed by the index of the leg to catch: its departure minus the
 * previous transit leg's arrival minus the walks between them. [delays] (seconds, by part index) shift both vehicles by
 * the whole minutes they are shown as (iOS Trip.swift `transferBuffers`).
 */
fun Journey.transferBuffers(delays: Map<Int, Int> = emptyMap()): Map<Int, TransferBuffer> {
    val buffers = mutableMapOf<Int, TransferBuffer>()
    var arriving: Int? = null
    var walkMillis = 0L
    parts.forEachIndexed { index, part ->
        if (part.isWalk) {
            walkMillis += Duration.between(part.startDeparture, part.endArrival).toMillis()
            return@forEachIndexed
        }
        arriving?.let { previous ->
            val arrival = parts[previous].endArrival.expected(delays[previous])
            val departure = part.startDeparture.expected(delays[index])
            val millis = Duration.between(arrival, departure).toMillis() - walkMillis
            buffers[index] = TransferBuffer(Math.floorDiv(millis, 60_000L).toInt())
        }
        arriving = index
        walkMillis = 0
    }
    return buffers
}

/** A fare zone border on a leg: [index] of the first stop in the new zone. */
data class ZoneChange(val index: Int, val from: String, val to: String)

/** The fare zone borders the leg crosses. Stops without a zone are skipped. */
val List<PartStop>.zoneChanges: List<ZoneChange>
    get() {
        val changes = mutableListOf<ZoneChange>()
        var current: String? = null
        forEachIndexed { index, stop ->
            val zone = stop.zone ?: return@forEachIndexed
            current?.let { if (it != zone) changes += ZoneChange(index, it, zone) }
            current = zone
        }
        return changes
    }

/** B-API `trip/{id}/`: the run's stops, times in minutes after midnight of its service day, Bratislava time. */
data class BApiTrip(
    val trip_id: Int,
    /** Seconds; null until the vehicle runs. */
    val trip_delay: Int?,
    val stop_times: List<BApiStopTime>?,
)

data class BApiStopTime(
    val stop_id: Int,
    val station_id: Int?,
    val stop_gps: StopGps?,
    val stop_code: String?,
    val stop_name: String?,
    /** Minutes after midnight of the trip's service day; over 1440 after midnight. */
    val arrival: Int,
    val departure: Int,
    val zone: String?,
)

/**
 * [part]'s leg: its boarding stop (the one at the part's Bratislava start minute when the run passes it twice), then
 * the first alighting stop after it. Null when the part has no B-API stop ids or the run does not serve them.
 */
fun BApiTrip.partStops(part: Part, stops: List<Stop>): List<PartStop>? {
    val stopTimes = stop_times.orEmpty()
    val start = part.startDeparture.atZone(BRATISLAVA)
    val startMinute = start.hour * 60 + start.minute
    val boardings = stopTimes.indices.filter { stopTimes[it].stop_id == part.startStopId }
    val first = boardings.firstOrNull { stopTimes[it].departure % 1440 == startMinute } ?: boardings.firstOrNull() ?: return null
    val last = (first + 1 until stopTimes.size).firstOrNull { stopTimes[it].stop_id == part.endStopId } ?: return null
    val serviceDay = start.toLocalDate().minusDays((stopTimes[first].departure / 1440).toLong())
    return stopTimes.subList(first, last + 1).map { stopTime ->
        val platform = stopTime.stop_code?.takeIf { it.isNotEmpty() }
        PartStop(
            name = stopTime.stop_name.orEmpty(),
            platform = platform,
            time = serviceDayTime(serviceDay, if (stopTime.stop_id == part.endStopId) stopTime.arrival else stopTime.departure),
            gps = stopTime.stop_gps,
            zone = stopTime.zone,
            isRequestStop = false,
            stopId = stops.stop(stopTime.station_id, stopTime.stop_name, platform)?.id,
        )
    }
}

/** The Bratislava wall-clock time [minutes] after midnight of [serviceDay]; over 1440 falls on the next day. */
fun serviceDayTime(serviceDay: LocalDate, minutes: Int): Instant =
    serviceDay.plusDays((minutes / 1440).toLong()).atTime(minutes % 1440 / 60, minutes % 60).atZone(BRATISLAVA).toInstant()

/** The stop of a station, or else the first one with that name, preferring one with the platform letter. */
fun List<Stop>.stop(stationId: Int?, name: String?, platform: String?): Stop? {
    val candidates = filter { if (stationId != null) it.station_id == stationId else name != null && it.name == name }
    return candidates.firstOrNull { stop -> stop.platform_labels?.any { it.label == platform } == true } ?: candidates.firstOrNull()
}

/**
 * Magic API `timetable/shape`: a ride along the roads or tracks, one segment of [lat, lon] points from near each stop
 * to near the next; null where unknown.
 */
data class TimetableShape(val segments: List<List<List<Double>?>?>?)

/** The request (relative to the Magic API) for a ride of [line] through [stops]; a "+" in the line stays a "+". */
fun timetableShapeEndpoint(line: String, stops: List<StopGps>): String {
    val points = stops.joinToString(";") { String.format(Locale.ROOT, "%.5f,%.5f", it.lat, it.lon) }
    return "timetable/shape?line=${URLEncoder.encode(line, "UTF-8").replace("+", "%20")}&stops=$points"
}

/** The shape request of a ride whose every leg stop has a position; its two ends alone match the wrong stretch. */
val Part.shapeEndpoint: String?
    get() {
        val rideStops = stops.orEmpty()
        val gps = rideStops.mapNotNull { it.gps }
        val line = routeShortName
        if (isWalk || line == null || gps.size < 2 || gps.size != rideStops.size) return null
        return timetableShapeEndpoint(line, gps)
    }

/**
 * The ride through [stops], the ones requested, with each known segment in place of the straight line between its two
 * stops; straight throughout unless there is a segment for each two consecutive stops.
 */
fun TimetableShape.path(stops: List<StopGps>): List<StopGps> {
    val segments = segments.orEmpty()
    if (stops.size < 2 || segments.size != stops.size - 1) return stops
    val path = mutableListOf<StopGps>()
    segments.forEachIndexed { index, segment ->
        val points = segment.orEmpty().mapNotNull { point -> point?.takeIf { it.size == 2 }?.let { StopGps(lon = it[1], lat = it[0]) } }
        val piece = if (points.size > 1) points else listOf(stops[index], stops[index + 1])
        // A point where one piece ends and the next starts, as the stop between two straight lines, comes once.
        path += if (piece.first() == path.lastOrNull()) piece.drop(1) else piece
    }
    return path
}

/** A ride's stops, or its two ends until they load; a walk from where the previous part ends to where the next starts. */
fun partPoints(parts: List<Part>, index: Int): List<StopGps> {
    val part = parts[index]
    if (!part.isWalk) part.stops?.mapNotNull { it.gps }?.takeIf { it.size > 1 }?.let { return it }
    val start = part.startStopGps ?: parts.getOrNull(index - 1)?.endStopGps
    val end = part.endStopGps ?: parts.getOrNull(index + 1)?.startStopGps
    return listOfNotNull(start, end)
}

/** What the trip map shows. */
sealed interface MapFocus {
    data object Route : MapFocus

    /** A leg; a walk goes on through the walks after it. */
    data class Leg(val index: Int) : MapFocus

    /** The change into the ride at this index: the previous ride's end (or the journey's start), the walks, its start. */
    data class Change(val to: Int) : MapFocus

    data class StopAt(val gps: StopGps) : MapFocus
}

/** The positions to fit for [target]. */
fun focusPoints(target: MapFocus, parts: List<Part>): List<StopGps> = when (target) {
    MapFocus.Route -> parts.indices.flatMap { partPoints(parts, it) }
    is MapFocus.Leg -> {
        val index = target.index
        val last = if (parts[index].isWalk) {
            ((index until parts.size).firstOrNull { !parts[it].isWalk } ?: parts.size) - 1
        } else {
            index
        }
        (index..last).flatMap { partPoints(parts, it) }
    }
    is MapFocus.Change -> {
        val from = (0 until target.to).lastOrNull { !parts[it].isWalk }
        ((from ?: 0)..target.to).flatMap { index ->
            val points = partPoints(parts, index)
            when (index) {
                from -> points.takeLast(1)
                target.to -> points.take(1)
                else -> points
            }
        }
    }
    is MapFocus.StopAt -> listOf(target.gps)
}

/**
 * This leg's departure on a stop's live board: the same line and platform (regional rows have -1), scheduled within
 * 3 minutes, the closest in time, then by headsign. Once [busID], the vehicle already followed, is known, only it matches.
 */
fun Part.liveConnection(connections: List<MHDTableData>, platform: Int?, scheduled: Instant, busID: String? = null): MHDTableData? {
    val time = scheduled.epochSecond
    return connections
        .filter {
            it.line == routeShortName && abs(it.departureTimeCP - time) <= 180 &&
                (platform == null || it.platform == platform || it.platform == -1) && (busID == null || it.busID == busID)
        }
        .minWithOrNull(compareBy({ abs(it.departureTimeCP - time) }, { if (it.headsign == tripHeadsign) 0 else 1 }))
}

/** The stop whose live board lists a leg's vehicle, that board's platform id, and when the vehicle is due there. */
data class LiveBoardStop(val stop: Stop, val platform: Int?, val scheduled: Instant)

/**
 * Where this leg's vehicle shows on a live board at [at], its boarding stop when null (iOS PartLiveTracker.init): the
 * leg stop's imhd stop, else the stop by station (boarding stop only), name and platform. Null for walks and stops
 * missing from [stops].
 */
fun Part.liveBoardStop(stops: List<Stop>, at: PartStop? = null): LiveBoardStop? {
    if (isWalk) return null
    val target = at ?: this.stops?.firstOrNull()
    val platformLabel = target?.platform ?: startStopCode
    val boardStop = target?.stopId?.let { id -> stops.firstOrNull { it.id == id } }
        ?: stops.stop(if (at == null) startStationId else null, target?.name ?: startStopName, platformLabel)
        ?: return null
    val platform = boardStop.platform_labels?.firstOrNull { it.label == platformLabel }?.id?.toIntOrNull()
    return LiveBoardStop(boardStop, platform, target?.time ?: startDeparture)
}

enum class StopsState { LOADING, FAILED }

/** The journey shown in the trip detail, completed while it is on screen (iOS TripDetailModel). */
data class TripDetailState(
    val journey: Journey,
    /** When the live windows were last evaluated; moves every minute while the screen is started. */
    val now: Instant = Instant.now(),
    val stopsState: Map<Int, StopsState> = emptyMap(),
    /** Departures matched on the boarding stop's live board, by part index. */
    val liveConnections: Map<Int, MHDTableData> = emptyMap(),
    /** B-API trip delays (seconds) by part index, fetched with the stops while the leg was live; null when not running. */
    val tripDelays: Map<Int, Int?> = emptyMap(),
    /** Rides whose stops in between are shown. */
    val expanded: Set<Int> = emptySet(),
    /** The trip followed in the live notification while it is this journey. */
    val followed: FollowedTrip? = null,
) {
    /**
     * The followed trip's live delay, so the step it shows agrees; else the live board's online delay, else the B-API's
     * (null when its vehicle was not running), else the search's.
     */
    fun knownDelaySeconds(index: Int): Int? {
        followed?.liveDelays?.get(index)?.let { return it }
        liveConnections[index]?.takeIf { it.type == "online" }?.let { return it.delay * 60 }
        if (tripDelays.containsKey(index)) return tripDelays[index]
        return journey.parts[index].delaySeconds
    }

    /** The delay to show and to plan changes with while the leg is live, until its expected arrival. */
    fun delaySeconds(index: Int): Int? {
        val delay = knownDelaySeconds(index)
        return delay.takeIf { journey.parts[index].isLive(now, delay) }
    }

    /** Seconds late by part index while each leg is live. */
    val delays: Map<Int, Int> get() = journey.parts.indices.mapNotNull { index -> delaySeconds(index)?.let { index to it } }.toMap()
}
