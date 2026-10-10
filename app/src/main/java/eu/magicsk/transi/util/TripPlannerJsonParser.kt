package eu.magicsk.transi.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import eu.magicsk.transi.data.models.BRATISLAVA
import eu.magicsk.transi.data.models.IMHD_JOURNEY_PREFIX
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.PartStop
import eu.magicsk.transi.data.models.RAPTOR_JOURNEY_PREFIX
import eu.magicsk.transi.data.models.ROUTE_TYPE_WALK
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RaptorJourney
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID

private const val WALK_TYPE = "🚶"
private const val IMHD_RIDE_ROUTE_TYPE = 1
private val cepoTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")

/** R-API journeys; ones without parts are dropped. Leg stops stay null until the detail loads them. */
fun mapRaptorJourneys(journeys: List<RaptorJourney>): List<Journey> = journeys.mapNotNull { journey ->
    val parts = journey.parts ?: return@mapNotNull null
    Journey(
        id = RAPTOR_JOURNEY_PREFIX + UUID.randomUUID(),
        parts = parts.map {
            Part(
                startStopName = it.start_stop_name,
                endStopName = it.end_stop_name,
                startStopCode = it.start_stop_code.nonEmpty(),
                endStopCode = it.end_stop_code.nonEmpty(),
                startDeparture = parseUtc(it.start_departure),
                endArrival = parseUtc(it.end_arrival),
                routeType = it.route_type,
                tripHeadsign = it.trip_headsign,
                routeShortName = it.route_short_name,
                tripId = it.trip_id,
                tripRouteId = it.trip_route_id,
                startStopId = it.start_stop_id,
                endStopId = it.end_stop_id,
                startStationId = it.start_station_id,
                endStationId = it.end_station_id,
                startStopGps = it.start_stop_gps,
                endStopGps = it.end_stop_gps,
                delaySeconds = it.trip_delay,
                zones = it.trip_zones?.map(Int::toString),
                ticketId = it.ticket_id,
            )
        },
        zones = journey.zones?.map(Int::toString),
        ticketId = journey.ticket_id,
    )
}

/**
 * The I-API (imhd cepo) response as journeys, or null when [json] is not a JSON object. Times are Bratislava wall-clock
 * times. [stops] (the stops list) gives GPS, station and imhd stop ids, joined on `stopPoleID`, else on name and platform.
 */
fun mapCepoJourneys(json: String, stops: List<Stop>): List<Journey>? {
    val root = try {
        JsonParser.parseString(json)
    } catch (_: JsonParseException) {
        return null
    }
    if (!root.isJsonObject) return null

    val stopsByPlatformId = HashMap<String, Stop>()
    stops.forEach { stop -> stop.platform_labels?.forEach { stopsByPlatformId.putIfAbsent(it.id, stop) } }
    fun magicStop(stop: JsonObject?, platform: String?): Stop? = stop?.let {
        it.string("stopPoleID")?.let(stopsByPlatformId::get) ?: stops.find(it.string("name"), platform)
    }

    // ponytail: a journey missing its times or parts is skipped; iOS drops the whole response instead.
    return root.asJsonObject.get("journeys").elements().mapNotNull { element ->
        val journey = element.asObject() ?: return@mapNotNull null
        val lines = journey.get("lines")?.takeIf { it.isJsonArray }?.asJsonArray
        val mappedLines = journey.get("linesMapping").elements().map { index ->
            val line = index.asIntOrNull()?.let { lines?.elements()?.getOrNull(it) }?.takeIf { it.isJsonArray }?.asJsonArray
            line?.takeIf { it.size() > 1 }?.get(1)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                ?: "Err"
        }
        val iParts = journey.get("parts").elements().mapNotNull { it.asObject() }
        if (iParts.isEmpty()) return@mapNotNull null

        val parts = iParts.mapIndexed { index, part ->
            val iStops = part.get("stops").elements().mapNotNull { it.asObject() }
            val startStop = iStops.firstOrNull()
            val endStop = iStops.lastOrNull()
            val isWalk = part.string("type") == WALK_TYPE
            val startStopCode = startStop?.string("label").nonEmpty()
            val endStopCode = endStop?.string("label").nonEmpty()
                ?: if (isWalk) iParts.getOrNull(index + 1)?.get("stops").elements().firstOrNull()?.asObject()
                    ?.string("label").nonEmpty() else null
            val departure = part.obj("departure")?.string("date")?.let(::parseCepoTime) ?: Instant.now()
            val arrival = part.obj("arrival")?.string("date")?.let(::parseCepoTime) ?: Instant.now()
            val startMagicStop = magicStop(startStop, startStopCode)
            val endMagicStop = magicStop(endStop, endStopCode)
            val partStops = if (isWalk) emptyList() else iStops.mapIndexed { stopIndex, iStop ->
                val isLast = stopIndex == iStops.lastIndex
                val time = if (isLast) iStop.obj("arrival") ?: iStop.obj("departure")
                else iStop.obj("departure") ?: iStop.obj("arrival")
                val platform = iStop.string("label").nonEmpty()
                val stop = magicStop(iStop, platform)
                PartStop(
                    name = iStop.string("name").orEmpty(),
                    platform = platform,
                    time = time?.string("date")?.let(::parseCepoTime) ?: if (isLast) arrival else departure,
                    gps = stop?.gps,
                    zone = fareZones(iStop.get("fare_zones")).firstOrNull(),
                    isRequestStop = iStop.string("request_stop") == "1",
                    stopId = stop?.id,
                )
            }
            Part(
                startStopName = startStop?.string("name"),
                endStopName = endStop?.string("name"),
                startStopCode = startStopCode,
                endStopCode = endStopCode,
                startDeparture = departure,
                endArrival = arrival,
                routeType = if (isWalk) ROUTE_TYPE_WALK else IMHD_RIDE_ROUTE_TYPE,
                tripHeadsign = part.string("destination"),
                routeShortName = mappedLines.getOrNull(index),
                startStationId = startMagicStop?.station_id,
                endStationId = endMagicStop?.station_id,
                startStopGps = startMagicStop?.gps,
                endStopGps = endMagicStop?.gps,
                lowFloor = if (isWalk) null else part.obj("attributes")?.get("lowfloor")
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean,
                stops = partStops.ifEmpty { null },
            )
        }
        Journey(
            id = IMHD_JOURNEY_PREFIX + UUID.randomUUID(),
            parts = parts,
            zones = journey.get("fare_zones")?.let(::fareZones),
        )
    }
}

/** `fare_zones` comes as a string, an array or nested objects; their values, in order. */
internal fun fareZones(element: JsonElement?): List<String> = when {
    element == null -> emptyList()
    element.isJsonPrimitive -> listOf(element.asString)
    element.isJsonArray -> element.asJsonArray.filter { it.isJsonPrimitive }.map { it.asString }
    element.isJsonObject -> element.asJsonObject.entrySet().flatMap { fareZones(it.value) }
    else -> emptyList()
}

val Stop.gps get() = StopGps(lon = lng, lat = lat)

/** The stop with [name], preferring one with the [platform] letter. */
private fun List<Stop>.find(name: String?, platform: String?): Stop? {
    val candidates = filter { name != null && it.name == name }
    return candidates.firstOrNull { stop -> stop.platform_labels?.any { it.label == platform } == true }
        ?: candidates.firstOrNull()
}

/** iOS falls back to now for an unreadable time. */
private fun parseUtc(value: String?): Instant = value?.let {
    try {
        Instant.parse(it)
    } catch (_: DateTimeParseException) {
        null
    }
} ?: Instant.now()

private fun parseCepoTime(value: String): Instant? = try {
    LocalDateTime.parse(value, cepoTimeFormat).atZone(BRATISLAVA).toInstant()
} catch (_: DateTimeParseException) {
    null
}

/** Train stops come with an empty platform letter. */
private fun String?.nonEmpty() = this?.takeIf { it.isNotEmpty() }

/** An array's items or an object's values (PHP sends sparse arrays as objects). */
private fun JsonElement?.elements(): List<JsonElement> = when {
    this == null -> emptyList()
    isJsonArray -> asJsonArray.toList()
    isJsonObject -> asJsonObject.entrySet().map { it.value }
    else -> emptyList()
}

private fun JsonElement.asObject(): JsonObject? = if (isJsonObject) asJsonObject else null

private fun JsonElement.asIntOrNull(): Int? =
    if (isJsonPrimitive && asJsonPrimitive.isNumber) asInt else null

private fun JsonObject.obj(key: String) = get(key)?.asObject()

private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
