package eu.magicsk.transi.data.models

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.ColorRes
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RegionalDeparture
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToLong

private fun JSONObject.stringOrNull(name: String): String? = if (isNull(name)) null else optString(name)

/**
 * One `tabs` message by stop id (`zastavka`), then platform: the departures of every platform it lists, including
 * platforms that have none left.
 */
fun parseTabs(data: JSONArray): Map<Int, Map<Int, List<MHDTableData>>> {
    val stops = mutableMapOf<Int, MutableMap<Int, List<MHDTableData>>>()
    for (j in 0 until data.length()) {
        val platformObject = data.optJSONObject(j) ?: continue
        val tab = platformObject.optJSONArray("tab")
        if (platformObject.isNull("nastupiste") || platformObject.isNull("zastavka") || tab == null) continue
        val platform = platformObject.optInt("nastupiste")
        val stopId = platformObject.optInt("zastavka")
        stops.getOrPut(stopId) { mutableMapOf() }[platform] = (0 until tab.length()).mapNotNull { i ->
            tab.optJSONObject(i)?.let { parseDeparture(it, platform, stopId) }
        }
    }
    return stops
}

private fun parseDeparture(json: JSONObject, platform: Int, stopId: Int): MHDTableData {
    val tripId = json.optLong("i", 0)
    return MHDTableData(
        id = "$tripId:$platform",
        tripId = tripId,
        line = json.stringOrNull("linka") ?: "Error",
        platform = platform,
        stopId = stopId,
        busID = json.stringOrNull("issi") ?: "Offline",
        headsign = json.stringOrNull("cielStr") ?: json.stringOrNull("konecnaZstr") ?: "Error",
        departureTimeCP = (json.optDouble("casCP", 0.0) / 1000 / 60).roundToLong() * 60,
        departureTime = json.optLong("cas", 0),
        delay = json.optInt("casDelta", 0),
        type = json.stringOrNull("typ") ?: "cp",
        currentStopId = json.optInt("tuZidx", -1),
        lastStopId = json.optInt("predoslaZidx", -1),
        lastStopName = (json.stringOrNull("predoslaZstr") ?: "none").replace("Bratislava, ", ""),
        stuck = json.optBoolean("uviaznute", false),
    )
}

fun parseVehicle(json: JSONObject) = MHDTableVehicle(
    id = json.optInt("id", 0),
    lf = json.optInt("lf", 0),
    ac = json.optInt("ac", 0),
    img = json.optInt("img", 0),
    imgt = json.optInt("imgt", 0),
    type = json.stringOrNull("type") ?: "error",
    issi = json.stringOrNull("issi") ?: "error",
)

/**
 * The socket replaces every row of each platform of [stopId] present in a `tabs` message and keeps the other
 * platforms. A message for another stop, sent before a stop change reached the server, changes nothing.
 */
fun replacePlatforms(rows: List<MHDTableData>, message: Map<Int, Map<Int, List<MHDTableData>>>, stopId: Int): List<MHDTableData> {
    val platforms = message[stopId].orEmpty()
    return rows.filter { it.platform !in platforms.keys } + platforms.values.flatten()
}

/**
 * Where a linked departure ([MHDTableData.id]) of [stopId] is listed, or -1. Socket ids repeat across stops, so a
 * board still showing another stop never matches.
 */
fun List<MHDTableData>.indexOfDeparture(stopId: Int, id: String): Int = indexOfFirst { it.id == id && it.stopId == stopId }

/**
 * The socket and regional feeds share no trip id and their timetables can be up to 3 minutes apart. That is close
 * enough to hide a live duplicate, but some lines run every 2 minutes, so a timetable-only row takes over, and hides,
 * live data only on the exact schedule. Regional platforms can be unknown (-1).
 */
fun isSameDeparture(a: MHDTableData, b: MHDTableData, tolerance: Long = 180): Boolean =
    a.line == b.line && (a.platform == b.platform || a.platform == -1 || b.platform == -1) &&
        abs(a.departureTimeCP - b.departureTimeCP) <= tolerance

/** The regional feed also lists trips arriving at their last stop; it shortens the bus station to "AS". */
fun terminates(headsign: String, stopName: String?): Boolean {
    val name = headsign.replace("Bratislava, ", "")
    return name == stopName || (name == "AS" && stopName == "Autobusová stanica")
}

/** iOS sortAndPublishConnections: what the board shows from both feeds, by expected time then id. */
fun mergeDepartures(socket: List<MHDTableData>, regional: List<MHDTableData>, nowSeconds: Long): List<MHDTableData> {
    val published = socket.map { row ->
        if (row.type != "online") {
            regional.firstOrNull { isSameDeparture(it, row, 0) }?.copy(platform = row.platform) ?: row
        } else {
            row
        }
    }
    val regionalOnly = regional.filter { regionalRow ->
        regionalRow.departureTimeCP < nowSeconds && socket.none {
            if (it.type == "online") isSameDeparture(it, regionalRow) else isSameDeparture(it, regionalRow, 0)
        }
    }
    return (published + regionalOnly).sortedWith(compareBy({ it.departureTime }, { it.id }))
}

/** The busID of regional rows: they name no vehicle, so a leg never follows one by it. */
const val REGIONAL_BUS_ID = "0:0"

/** Regional rows of [stop] that can still be boarded there, timed from the device's local midnight like iOS. */
fun regionalDepartures(departures: List<RegionalDeparture>, stop: Stop, now: ZonedDateTime): List<MHDTableData> {
    val startOfToday = now.toLocalDate().atStartOfDay(now.zone)
    val nowMillis = now.toInstant().toEpochMilli()
    // Like iOS, only rows with live data (a delay) are kept.
    return departures.mapNotNull { departure ->
        val delayMinutes = (departure.trip_delay ?: return@mapNotNull null) / 60
        val scheduled = startOfToday.plusMinutes(departure.departure.toLong())
        val scheduledSeconds = scheduled.toEpochSecond()
        MHDTableData(
            id = "${departure.trip_id}:${departure.stop_id}:$scheduledSeconds",
            tripId = departure.trip_id,
            line = departure.route_short_name.orEmpty(),
            platform = departure.stop_code?.let { code ->
                stop.platform_labels?.firstOrNull { it.label == code }?.id?.toIntOrNull()
            } ?: -1,
            stopId = stop.id,
            busID = REGIONAL_BUS_ID,
            headsign = departure.trip_headsign.orEmpty(),
            departureTimeCP = scheduledSeconds,
            departureTime = scheduled.plusMinutes(delayMinutes.toLong()).toInstant().toEpochMilli(),
            delay = delayMinutes,
            type = "online",
            currentStopId = -1,
            lastStopId = departure.last_station_id ?: -1,
            lastStopName = departure.last_station ?: "none",
            stuck = false,
        )
    }.filterNot {
        it.departureTime < nowMillis + 15_000 || terminates(it.headsign, stop.name)
    }
}

/**
 * iOS getDepartureTimeRemainingText in minutes: null for "now", 0 for "<1 min", otherwise whole minutes
 * (the board shows the clock time from 60 on).
 */
fun remainingMinutes(departureTime: Long, now: Long): Int? {
    val seconds = (departureTime - now) / 1000
    return when {
        seconds > 59 -> (seconds / 60).toInt()
        seconds > 0 -> 0
        else -> null
    }
}

/** iOS getDepartureTimeRemainingText: "N min", "<1 min", "now", or the clock time from 60 min on; "~" unless live. */
fun remainingText(context: Context, departureTime: Long, type: String, now: Long): String {
    val minutes = remainingMinutes(departureTime, now)
    val text = when {
        minutes == null -> context.getString(R.string.table_now)
        minutes == 0 -> context.getString(R.string.table_under_minute)
        minutes > 59 -> clockText(context, departureTime)
        else -> context.getString(R.string.table_minutes, minutes)
    }
    return if (type == "online") text else context.getString(R.string.table_approximate, text)
}

/** iOS getShortDepartureTimeRemainingText: "Nm", "<1", "now", or ":mm" of the clock time from 60 min on. */
fun shortRemainingText(context: Context, departureTime: Long, now: Long): String {
    val minutes = remainingMinutes(departureTime, now)
    return when {
        minutes == null -> context.getString(R.string.table_now)
        minutes == 0 -> context.getString(R.string.live_updates_short_under_minute)
        minutes > 59 -> context.getString(
            R.string.live_updates_short_clock, Instant.ofEpochMilli(departureTime).atZone(ZoneId.systemDefault()).minute
        )
        else -> context.getString(R.string.live_updates_short_minutes, minutes)
    }
}

/** The clock time in the device's 12/24-hour format. */
fun clockText(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(Date(millis))

/** iOS getDelayText as a string resource and its minutes argument. */
fun delayText(delay: Int, type: String): Pair<Int, Int> = when {
    type != "online" -> R.string.table_delay_offline to 0
    delay > 0 -> R.string.delay to delay
    delay < 0 -> R.string.inAdvance to -delay
    else -> R.string.table_no_delay to 0
}

/** iOS getDelayColor. */
@ColorRes
fun delayColor(delay: Int, type: String): Int = when {
    type != "online" -> R.color.table_grey
    delay in 0..1 -> R.color.table_green
    delay in 2..3 -> R.color.table_orange
    delay in 4..999 -> R.color.table_red
    else -> R.color.table_purple
}
