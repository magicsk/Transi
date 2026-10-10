package eu.magicsk.transi.data.models

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import java.time.Instant
import java.time.ZoneId

/** Every planner time is a Bratislava wall-clock time on the wire and on screen. */
val BRATISLAVA: ZoneId = ZoneId.of("Europe/Bratislava")

const val ROUTE_TYPE_WALK = 64
const val RAPTOR_JOURNEY_PREFIX = "r-"
const val IMHD_JOURNEY_PREFIX = "i-"

/** A planned trip from the R-API (raptor, id `r-…`) or the I-API (imhd cepo, id `i-…`). */
data class Journey(
    val id: String,
    val parts: List<Part>,
    /** Fare zones: R-API zone ids, I-API zone names. */
    val zones: List<String>?,
    /** R-API id of the cheapest single ticket that covers the whole journey. */
    val ticketId: Int? = null,
) {
    val isRaptor get() = id.startsWith(RAPTOR_JOURNEY_PREFIX)
    val isImhd get() = id.startsWith(IMHD_JOURNEY_PREFIX)

    /** The iOS `Journey ==`: the same rides at the same minutes between the same stops. Ids and walks don't count. */
    override fun equals(other: Any?) = other is Journey && sameRidesKey == other.sameRidesKey
    override fun hashCode() = sameRidesKey.hashCode()

    private val sameRidesKey: List<Any?>
        get() = parts.map {
            if (it.isWalk) ROUTE_TYPE_WALK
            else listOf(it.startDeparture.epochMinute, it.endArrival.epochMinute, it.routeShortName, it.startStopName, it.endStopName)
        }
}

data class Part(
    val startStopName: String?,
    val endStopName: String?,
    /** Platform letters, null when unknown. */
    val startStopCode: String?,
    val endStopCode: String?,
    val startDeparture: Instant,
    val endArrival: Instant,
    /** [ROUTE_TYPE_WALK] for a walk; R-API GTFS route types, 1 for every I-API ride. */
    val routeType: Int?,
    val tripHeadsign: String?,
    /** The line, like "72" or "N33". */
    val routeShortName: String?,
    /** R-API/B-API trip id, which loads the leg's stops. */
    val tripId: Int? = null,
    val tripRouteId: Int? = null,
    /** B-API platform ids of the boarding and alighting stops. */
    val startStopId: Int? = null,
    val endStopId: Int? = null,
    /** Station ids, equal to `Stop.station_id`. */
    val startStationId: Int? = null,
    val endStationId: Int? = null,
    val startStopGps: StopGps? = null,
    val endStopGps: StopGps? = null,
    /** Vehicle delay in seconds when the search ran; null when it was not running yet. */
    val delaySeconds: Int? = null,
    /** R-API zone ids of the ride. */
    val zones: List<String>? = null,
    val ticketId: Int? = null,
    val lowFloor: Boolean? = null,
    /** Every stop of the leg, boarding and alighting included; null until known (R-API legs). */
    val stops: List<PartStop>? = null,
) {
    val isWalk get() = routeType == ROUTE_TYPE_WALK
}

data class PartStop(
    val name: String,
    /** Platform letter. */
    val platform: String?,
    /** Scheduled time. */
    val time: Instant,
    val gps: StopGps?,
    /** Fare zone name, like "100". */
    val zone: String?,
    val isRequestStop: Boolean,
    /** imhd stop id (`Stop.id`). */
    val stopId: Int?,
)

private val Instant.epochMinute get() = Math.floorDiv(epochSecond, 60L)

/** Whole minutes between the clock minutes shown for [from] and [to], so 16:30:15 → 16:40:45 is 10 min. */
fun minutesBetween(from: Instant, to: Instant) = (to.epochMinute - from.epochMinute).toInt()

/** Gson for journeys and recent searches: [Instant]s as ISO-8601 strings. */
val plannerGson: Gson = GsonBuilder()
    .registerTypeAdapter(Instant::class.java, object : TypeAdapter<Instant>() {
        override fun write(out: JsonWriter, value: Instant) {
            out.value(value.toString())
        }

        override fun read(reader: JsonReader): Instant = Instant.parse(reader.nextString())
    }.nullSafe())
    .create()
