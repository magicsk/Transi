package eu.magicsk.transi.data.models

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.google.gson.Gson
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.util.clock
import eu.magicsk.transi.util.gps
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// The followed trip's step by step guidance (iOS TripProgress.swift), pure so the JVM tests replay it.

/** A text from the string resources with its arguments (strings, numbers or texts), resolved on screen. */
sealed interface TripText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : TripText
    data class Plural(@PluralsRes val id: Int, val count: Int) : TripText
    data class Raw(val text: String) : TripText
}

private fun text(@StringRes id: Int, vararg args: Any): TripText = TripText.Res(id, args.toList())

fun TripText.resolve(context: Context): String = when (this) {
    is TripText.Res -> if (args.isEmpty()) context.getString(id)
    else context.getString(id, *args.map { if (it is TripText) it.resolve(context) else it }.toTypedArray())
    is TripText.Plural -> context.resources.getQuantityString(id, count, count)
    is TripText.Raw -> text
}

/** Where the traveller is on a journey at a moment, from the leg stop times and the vehicles' delays. */
sealed interface TripPhase {
    /** Waiting for the first vehicle; its part index. */
    data class Board(val part: Int) : TripPhase

    /** On the vehicle of this part. */
    data class Ride(val part: Int) : TripPhase

    /** Between vehicles; the part index of the next one. */
    data class Change(val part: Int) : TripPhase

    /** Walking to the destination after the last vehicle; the part index of the walk. */
    data class Walk(val part: Int) : TripPhase

    data object Arrived : TripPhase
}

data class TripProgress(
    val phase: TripPhase,
    /** While riding, the index in [Part.legStops] of the next stop the vehicle reaches. */
    val nextStop: Int? = null,
    /** Stops left to the alighting stop; null when the leg's stop list did not load. */
    val stopsLeft: Int? = null,
    /** How far the ride has got, 0…1. */
    val fraction: Double? = null,
)

/** A leg stop whose live board the followed trip watches. */
data class TripTrackerTarget(val part: Int, val stop: Int)

/** A trip alert; [key] makes it fire once per step. */
data class TripAlert(val key: String, val title: TripText, val body: TripText)

/** The traveller's location, good to within [accuracy] metres. */
data class TripFix(val gps: StopGps, val accuracy: Double)

/** Walking pace over the straight line to a stop, slower than on foot to cover the streets' detours. */
const val TRIP_WALK_METERS_PER_SECOND = 1.0

/** A ride the traveller's location showed them not on, and the other connections found from where they are. */
data class TripMissed(
    val part: Int,
    /** They rode it for a while, so they got off or the vehicle left its route. */
    val boarded: Boolean,
    /** Null while the search runs. */
    val alternatives: TripAlternatives? = null,
)

/** Other connections to the destination after a missed ride. */
data class TripAlternatives(
    /** On the same lines as the rest of the trip: the next vehicles. */
    val sameRoute: List<Journey> = emptyList(),
    /** The rest, the earliest arrival first. */
    val others: List<Journey> = emptyList(),
) {
    val isEmpty get() = sameRoute.isEmpty() && others.isEmpty()

    /** The earliest arrival. */
    val best get() = (sameRoute + others).minByOrNull { it.expectedArrival }
}

enum class TripStep { BOARD, RIDE, CHANGE, WALK, ARRIVED, MISSED }

enum class TripWarning(@StringRes val text: Int) {
    TIGHT_CHANGE(R.string.trip_warning_tight),
    LIKELY_MISSED_CHANGE(R.string.trip_warning_missed),

    /** The walk to the next vehicle takes longer than it has left. */
    MAY_MISS(R.string.trip_warning_may_miss),
}

/** What the trip notification and the trip detail show for the current step (iOS TripActivityAttributes.ContentState). */
data class TripState(
    val step: TripStep,
    /** The line to board, ride or change to; null for walks and the arrival. */
    val line: String?,
    /** "Board at Hronská", "Get off in 3 stops", "Walk to platform D", "Arrived", "Missed 39". */
    val title: TripText,
    /** "Platform A · in 4 min", "Hlavná stanica, platform X", "350 m · 31 leaves in 6 min", "Take 83 at 14:49 from Hronská C". */
    val detail: TripText,
    /** When that vehicle leaves or arrives, delay included; the arrival for walks. */
    val time: Instant,
    /** Minutes late, negative when early; null without delay data. */
    val delay: Int? = null,
    /** How far the ride has got, 0…1; null outside rides. */
    val progress: Double? = null,
    /** The status bar chip: "4 min", "3 stops", "D · 6m". */
    val compact: TripText,
    /** The shortest form: "4m", "3", "D". */
    val minimal: TripText,
    /** The next change's spare time is short or gone. */
    val warning: TripWarning? = null,
    /** Alerts already shown, so each one fires once per step, also after the trip is restored. */
    val alerted: Set<String> = emptySet(),
)

/** One recomputation of the followed trip. */
data class TripUpdate(
    val progress: TripProgress,
    /** Includes [alert]'s key in its alerted keys. */
    val state: TripState,
    val alert: TripAlert?,
)

/** The followed trip as the screens show it. */
data class FollowedTrip(
    val journey: Journey,
    val progress: TripProgress?,
    val state: TripState,
    /** Seconds late by part index on the live boards. */
    val liveDelays: Map<Int, Int>,
    val missed: TripMissed?,
)

/** The delay in seconds while the vehicle reports live; timetable-only rows have none. */
val MHDTableData.liveDelaySeconds: Int? get() = if (type == "online") delay * 60 else null

/**
 * After the trip is restored the saved delays may be stale, so an arrived trip ends only once a live board answered or
 * 10 minutes after its arrival.
 */
fun tripCanEnd(now: Instant, arrival: Instant, awaitsLiveData: Boolean): Boolean =
    !awaitsLiveData || now >= arrival.plusSeconds(600)

/**
 * What the live boards showed of a followed journey's vehicles, by part index. Saved with the journey, so a restored
 * trip keeps following the same vehicles from where they got to. Its methods change it in place, like the iOS struct's
 * mutating ones; every collection is replaced, so a [copy] keeps the state it was taken at.
 */
data class TripLiveData(
    /** Seconds late. */
    var delays: Map<Int, Int> = emptyMap(),
    var busIDs: Map<Int, String> = emptyMap(),
    /** The last leg stop each vehicle left; none while the boards do not show where it is. */
    var passedStops: Map<Int, Int> = emptyMap(),
    /** When the followed vehicle was due to leave each board that listed it. */
    var listed: Map<TripTrackerTarget, Instant> = emptyMap(),
    /**
     * The last leg stop each vehicle left by the traveller's location while they ride it. It never moves the vehicle the
     * location is checked against, which comes from the boards and the timetable.
     */
    var located: Map<Int, Int> = emptyMap(),
    /** Rides the location showed the traveller on, past their boarding stop. */
    var boarded: Set<Int> = emptySet(),
    /** Since when the location has not shown the traveller on each ride. */
    var offRide: Map<Int, Instant> = emptyMap(),
    /** The ride the traveller is not on, so the trip offers other connections instead of its steps. */
    var missed: Int? = null,
    /** Rides the traveller kept following after the location found them off, which it no longer checks. */
    var kept: Set<Int> = emptySet(),
) {
    /** The last leg stop each vehicle left: on the boards, or by the location when that is further on. */
    val positions: Map<Int, Int>
        get() = (passedStops.keys + located.keys).associateWith { max(passedStops[it] ?: Int.MIN_VALUE, located[it] ?: Int.MIN_VALUE) }

    /**
     * One refresh of the trip: takes in [fix] against where the boards and the timetable had the vehicles a minute ago
     * (their whole-minute times and delays may run up to that far ahead), then the ride missed, with the other
     * connections in [found] (null until the search answers).
     */
    fun locate(fix: TripFix?, journey: Journey, found: List<Journey>?, now: Instant): TripMissed? {
        if (fix != null) {
            val progress = journey.progress(now.minusSeconds(60), journey.delays(delays), passedStops)
            receive(fix, journey, progress, now)
        }
        return missed?.let { index ->
            TripMissed(index, index in boarded, found?.let { journey.alternatives(it, index, now) })
        }
    }

    /**
     * Takes in the traveller's location while [progress], from the boards and the timetable, has them riding. On the
     * vehicle's line and not more than a stop (or 500 m, between far stops) behind it, they ride it, and the stop they
     * are at counts as reached; near the alighting stop they got off there. Otherwise for 45 s they missed it, once the
     * vehicle is that far ahead or they are off its line. Without a live delay the vehicle may only be late: it is
     * missed only by staying at the boarding stop or leaving the line, and not before 5 minutes after it was due.
     */
    fun receive(fix: TripFix, journey: Journey, progress: TripProgress, now: Instant) {
        if (missed != null) return
        val index = (progress.phase as? TripPhase.Ride)?.part ?: return
        val part = journey.parts.getOrNull(index) ?: return
        val stops = part.legStops
        if (index in kept || located[index] == stops.size - 1) return
        val vehicle = (progress.fraction ?: 0.0) * (stops.size - 1)
        val place = part.placement(fix, vehicle) ?: return
        val isLive = delays[index] != null
        val behind = place.stop < vehicle - 1 || place.meters(vehicle) - place.along > 500
        val rides = place.onLine && (!behind || !isLive && place.along >= 150)
        val alighted = stops.last().gps?.let { fix.gps.distance(it) <= 300 + fix.accuracy } ?: false
        if (rides || alighted) {
            offRide = offRide - index
            if (rides && place.along >= 150) boarded = boarded + index
            val next = if (rides) place.stops.indexOfFirst { it > place.along + 40 }.takeIf { it >= 0 } ?: stops.size else stops.size
            located = located + (index to max(located[index] ?: 0, next - 1))
            return
        }
        if (!isLive && now < part.startDeparture.plusSeconds((part.delaySeconds ?: 0) + 300L)) return
        val since = offRide[index] ?: now
        offRide = offRide + (index to since)
        if (Duration.between(since, now).seconds >= 45) missed = index
    }

    /**
     * Takes in the leg's departure on [target]'s board, which only reports the vehicle followed (from [busIDs], or the
     * first one live), null while the board does not list it. A stop's board lists a vehicle with the stop it left last
     * (not this one) until it leaves this stop, then drops it.
     */
    fun receive(connection: MHDTableData?, target: TripTrackerTarget, journey: Journey, now: Instant) {
        val index = target.part
        val stops = journey.parts.getOrNull(index)?.legStops.orEmpty()
        val delay = connection?.liveDelaySeconds
        if (connection != null && delay != null) {
            delays = delays + (index to delay)
            busIDs = busIDs + (index to connection.busID)
            listed = listed + (target to Instant.ofEpochMilli(connection.departureTime))
            // A loop line passes a stop twice, so take the one nearest before this stop.
            val left = stops.indices.lastOrNull { it < target.stop && sameStop(stops[it].name, connection.lastStopName) }
            if (left != null) passedStops = passedStops + (index to max(passedStops[index] ?: left, left))
        } else if (target.stop > 0 && target.stop < stops.size) {
            val vehicleDelay = (journey.delays(delays)[index] ?: 0).toLong()
            val due = listed[target]
            if (due != null) {
                // Dropped from the board: it left this stop, unless the board only reconnected before it was due.
                if (now >= due) passedStops = passedStops + (index to max(passedStops[index] ?: target.stop, target.stop))
            } else if (now >= stops[target.stop].time.plusSeconds(vehicleDelay + 180)) {
                // Never listed here and long past due (the board-matching tolerance): the vehicle went by unseen or the
                // boards stopped listing it. Count by the delayed timetable until a board lists it again.
                passedStops = passedStops - index
            }
        }
    }
}

/** A followed trip as saved, to restore it after the process ends. */
data class SavedTrip(val journey: Journey, val live: TripLiveData, val alerted: Set<String>) {
    /** Over at [now]: arrived, and past when a restored trip would end without live data (a missed one at once). */
    fun isOver(now: Instant): Boolean {
        val update = journey.update(now, journey.delays(live.delays), live.positions)
        return update.progress.phase == TripPhase.Arrived && (live.missed != null || tripCanEnd(now, update.state.time, true))
    }
}

/** Gson for [SavedTrip]: [TripLiveData.listed] has object keys. */
val savedTripGson: Gson = plannerGson.newBuilder().enableComplexMapKeySerialization().create()

/** Seconds late by part index: the live delay, else the one from the B-API or the search. */
fun Journey.delays(live: Map<Int, Int>): Map<Int, Int> {
    val delays = live.toMutableMap()
    parts.forEachIndexed { index, part ->
        if (delays[index] == null) part.delaySeconds?.let { delays[index] = it }
    }
    return delays
}

/**
 * [delays] (seconds, by part index) shift each vehicle's stop times; [passedStops] is the leg stop index each vehicle left
 * last on the live boards and wins over the timetable when counting the stops left.
 */
fun Journey.progress(now: Instant, delays: Map<Int, Int>, passedStops: Map<Int, Int> = emptyMap()): TripProgress {
    val transit = parts.indices.filter { !parts[it].isWalk }
    fun delay(index: Int?): Long = (index?.let(delays::get) ?: 0).toLong()
    for (index in transit) {
        val part = parts[index]
        if (now < part.startDeparture.plusSeconds(delay(index))) {
            return TripProgress(if (index == transit.first()) TripPhase.Board(index) else TripPhase.Change(index))
        }
        if (now < part.endArrival.plusSeconds(delay(index))) return ride(index, part, now, delay(index), passedStops[index])
    }
    val walk = parts.lastIndex
    if (walk >= 0 && walk != transit.lastOrNull() && now < parts[walk].endArrival.plusSeconds(delay(transit.lastOrNull()))) {
        return TripProgress(TripPhase.Walk(walk))
    }
    return TripProgress(TripPhase.Arrived)
}

private fun ride(index: Int, part: Part, now: Instant, delay: Long, passed: Int?): TripProgress {
    val times = part.legStops.map { it.time.plusSeconds(delay) }
    if (times.size <= 1) return TripProgress(TripPhase.Ride(index))
    // The vehicle's own position wins.
    val next = passed?.let { min(it + 1, times.size) } ?: max(times.indexOfFirst { it > now }.takeIf { it >= 0 } ?: (times.size - 1), 1)
    val previous = times[next - 1]
    val gap = seconds(previous, times[min(next, times.size - 1)])
    val between = if (gap > 0) (seconds(previous, now) / gap).coerceIn(0.0, 1.0) else 1.0
    val fraction = min((next - 1 + between) / (times.size - 1), 1.0)
    return TripProgress(
        TripPhase.Ride(index),
        nextStop = next,
        stopsLeft = if (part.stops == null) null else times.size - next,
        // Whole percents, so the notification is not updated for invisible changes.
        fraction = (fraction * 100).roundToLong() / 100.0,
    )
}

private fun seconds(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 1000.0

/**
 * The live boards to watch: the boarding stop until the vehicle leaves, then the next stop it reaches, plus the next
 * vehicle's boarding stop while a change lies ahead.
 */
fun Journey.trackerTargets(progress: TripProgress): List<TripTrackerTarget> = when (val phase = progress.phase) {
    is TripPhase.Board -> listOf(TripTrackerTarget(phase.part, 0))
    is TripPhase.Change -> listOf(TripTrackerTarget(phase.part, 0))
    is TripPhase.Ride -> {
        val part = parts[phase.part]
        val last = part.legStops.lastIndex
        var stop = min(progress.nextStop ?: last, last)
        // A vehicle ending its run is no departure, so the terminus board will not list it.
        val headsign = part.tripHeadsign
        if (stop == last && headsign != null && terminates(headsign, part.endStopName)) stop -= 1
        listOfNotNull(
            TripTrackerTarget(phase.part, stop).takeIf { stop > 0 },
            nextTransit(phase.part)?.let { TripTrackerTarget(it, 0) },
        )
    }
    is TripPhase.Walk, TripPhase.Arrived -> emptyList()
}

/**
 * The step at [now], and the alert due that is not in [alerted] yet. [fix] adds the walk to the next stop; [missed]
 * replaces the steps with the other connections.
 */
fun Journey.update(
    now: Instant,
    delays: Map<Int, Int>,
    passedStops: Map<Int, Int> = emptyMap(),
    alerted: Set<String> = emptySet(),
    fix: TripFix? = null,
    missed: TripMissed? = null,
): TripUpdate {
    val progress = progress(now, delays, passedStops)
    val state = missed?.let { missedState(it, delays, alerted) } ?: state(progress, now, delays, alerted, fix)
    val alert = alert(progress, state, now, delays, missed)
    return TripUpdate(progress, if (alert == null) state else state.copy(alerted = state.alerted + alert.key), alert)
}

private fun Journey.state(progress: TripProgress, now: Instant, delays: Map<Int, Int>, alerted: Set<String>, fix: TripFix?): TripState {
    val lastTransit = parts.indexOfLast { !it.isWalk }.takeIf { it >= 0 }
    fun shifted(date: Instant, index: Int?) = date.expected(index?.let(delays::get))
    fun minutesLate(index: Int) = delays[index]?.let(::delayMinutes)
    val phase = progress.phase
    // A change starts at the arriving vehicle's whole-minute arrival, when it may still be a stop away, so the walk counts
    // from the location only a minute later.
    val arriving = (phase as? TripPhase.Change)?.let { change -> parts.subList(0, change.part).indexOfLast { !it.isWalk }.takeIf { it >= 0 } }
    val location = fix.takeUnless { arriving != null && now < shifted(parts[arriving].endArrival, arriving).plusSeconds(60) }

    /** Metres to [gps] while the traveller is further than the location tells apart from being there. */
    fun away(gps: StopGps?): Double? {
        if (location == null || gps == null) return null
        val meters = location.gps.distance(gps)
        return meters.takeIf { it > max(50.0, location.accuracy) }
    }

    val buffer = upcomingChange(phase)?.let { transferBuffers(delays)[it] }
    var warning = when {
        buffer == null -> null
        buffer.isLikelyMissed -> TripWarning.LIKELY_MISSED_CHANGE
        buffer.isTight -> TripWarning.TIGHT_CHANGE
        else -> null
    }
    // Searches and older trips may only know the station's position, up to ~150 m from the platform.
    val boarding = boarding(phase)
    if (warning != TripWarning.LIKELY_MISSED_CHANGE && boarding != null) {
        val meters = away(parts[boarding].legStops.first().gps)
        if (meters != null && (meters - 150) / TRIP_WALK_METERS_PER_SECOND > seconds(now, shifted(parts[boarding].startDeparture, boarding))) {
            warning = TripWarning.MAY_MISS
        }
    }

    fun content(
        step: TripStep, line: String?, title: TripText, detail: TripText, time: Instant, delay: Int? = null,
        fraction: Double? = null, compact: TripText, minimal: TripText,
    ) = TripState(step, line, title, detail, time, delay, fraction, compact, minimal, warning, alerted)

    return when (phase) {
        is TripPhase.Board -> {
            val part = parts[phase.part]
            val time = shifted(part.startDeparture, phase.part)
            val countdown = Countdown(time, now)
            val stop: Any = part.startStopName ?: text(R.string.trip_the_stop)
            val meters = away(part.legStops.first().gps)
            val code = part.startStopCode
            val detail = if (meters != null) {
                val distance = code?.let { text(R.string.trip_distance_to_platform, distanceText(meters), it) } ?: distanceText(meters)
                text(R.string.trip_dot, distance, text(R.string.trip_leaves, countdown.phrase))
            } else {
                code?.let { text(R.string.trip_dot, text(R.string.trip_platform, it), countdown.phrase) } ?: countdown.phrase
            }
            content(
                TripStep.BOARD, part.routeShortName, text(if (meters == null) R.string.trip_board_at else R.string.trip_walk_to, stop),
                detail, time, minutesLate(phase.part), compact = countdown.short, minimal = countdown.minimal,
            )
        }
        is TripPhase.Ride -> {
            val part = parts[phase.part]
            val time = shifted(part.endArrival, phase.part)
            val countdown = Countdown(time, now)
            val stopsLeft = progress.stopsLeft
            val title = when (stopsLeft) {
                null -> text(R.string.trip_get_off_when, countdown.phrase)
                0 -> text(R.string.trip_get_off_now)
                1 -> text(R.string.trip_get_off_next)
                else -> TripText.Plural(R.plurals.trip_get_off_in, stopsLeft)
            }
            val name = part.endStopName
            val platform = part.endStopCode?.let { text(R.string.trip_platform_lowercase, it) }
            val detail = when {
                name != null && platform != null -> text(R.string.trip_comma, name, platform)
                else -> platform ?: TripText.Raw(name.orEmpty())
            }
            content(
                TripStep.RIDE, part.routeShortName, title, detail, time, minutesLate(phase.part), progress.fraction,
                compact = stopsLeft?.let { if (it == 0) text(R.string.trip_countdown_now) else TripText.Plural(R.plurals.trip_stops_left, it) }
                    ?: countdown.short,
                minimal = stopsLeft?.let { TripText.Raw(it.toString()) } ?: countdown.minimal,
            )
        }
        is TripPhase.Change -> {
            val part = parts[phase.part]
            val time = shifted(part.startDeparture, phase.part)
            val countdown = Countdown(time, now)
            val arrivingPart = arriving?.let(parts::get)
            val sameStop = arrivingPart?.endStopName == part.startStopName
            val platform = part.legStops.first().gps
            val meters = away(platform)
            // Where the location and the stop's position are known, waiting is being at the stop.
            val waits = if (location != null && platform != null) meters == null
            else sameStop && arrivingPart?.endStopCode == part.startStopCode
            val code = part.startStopCode
            val title = if (code != null) text(if (waits) R.string.trip_wait_at_platform else R.string.trip_walk_to_platform, code)
            else text(if (waits) R.string.trip_wait_at else R.string.trip_walk_to, part.startStopName ?: text(R.string.trip_the_next_stop))
            val leaves = text(R.string.trip_vehicle_leaves, part.routeShortName ?: text(R.string.trip_the_next_vehicle), countdown.phrase)
            val name = part.startStopName
            val walk = meters?.let { if (sameStop || name == null) distanceText(it) else text(R.string.trip_distance_to, distanceText(it), name) }
            val where: Any? = walk ?: name.takeUnless { sameStop }
            content(
                TripStep.CHANGE, part.routeShortName, title, where?.let { text(R.string.trip_dot, it, leaves) } ?: leaves,
                time, minutesLate(phase.part),
                compact = code?.let { text(R.string.trip_dot, it, countdown.minimal) } ?: countdown.short,
                minimal = code?.let(TripText::Raw) ?: countdown.minimal,
            )
        }
        is TripPhase.Walk -> {
            val walk = parts[phase.part]
            val time = shifted(walk.endArrival, lastTransit)
            val countdown = Countdown(time, now)
            content(
                TripStep.WALK, null, text(R.string.trip_walk_to, walk.endStopName ?: text(R.string.trip_your_destination)),
                away(walk.endStopGps)?.let { text(R.string.trip_dot, distanceText(it), text(R.string.trip_arrive_lowercase, countdown.phrase)) }
                    ?: text(R.string.trip_arrive, countdown.phrase),
                time, compact = countdown.short, minimal = countdown.minimal,
            )
        }
        TripPhase.Arrived -> content(
            TripStep.ARRIVED, null, text(R.string.trip_arrived), TripText.Raw(parts.lastOrNull()?.endStopName.orEmpty()),
            parts.lastOrNull()?.let { shifted(it.endArrival, lastTransit) } ?: now,
            compact = text(R.string.trip_arrived), minimal = text(R.string.trip_arrived_minimal),
        )
    }
}

/** The other connections in place of the steps, the soonest arrival first. */
private fun Journey.missedState(missed: TripMissed, delays: Map<Int, Int>, alerted: Set<String>): TripState {
    val part = parts.getOrNull(missed.part)
    val line = part?.routeShortName
    val ride = missed.alternatives?.best?.parts?.firstOrNull { !it.isWalk }
    val leaves = ride?.let { it.startDeparture.expected(it.delaySeconds) }
    val detail = when {
        ride != null && leaves != null -> {
            val name: Any = ride.startStopName ?: text(R.string.trip_the_stop)
            text(
                R.string.trip_take, ride.routeShortName ?: text(R.string.trip_the_next_vehicle_lowercase), clock(leaves),
                ride.startStopCode?.let { text(R.string.trip_stop_platform, name, it) } ?: name,
            )
        }
        missed.alternatives == null -> text(R.string.trip_finding_connections)
        else -> text(R.string.trip_no_connections)
    }
    return TripState(
        TripStep.MISSED, ride?.routeShortName ?: line,
        if (missed.boarded) text(R.string.trip_off_route) else text(R.string.trip_missed, line ?: text(R.string.trip_the_vehicle)),
        detail,
        // Until there is another one, when the missed vehicle left.
        leaves ?: part?.let { it.startDeparture.expected(delays[missed.part]) } ?: Instant.EPOCH,
        compact = text(R.string.trip_missed_compact), minimal = text(R.string.trip_missed_minimal), alerted = alerted,
    )
}

/**
 * The most urgent alert due: a missed ride, a likely missed change, the walk to a vehicle that may leave first, the
 * platform at a change, then getting off.
 */
private fun Journey.alert(progress: TripProgress, state: TripState, now: Instant, delays: Map<Int, Int>, missed: TripMissed?): TripAlert? {
    if (missed != null) {
        // Once the search answered, so the alert says what to take.
        val alert = TripAlert("lost-${missed.part}", state.title, state.detail)
        return alert.takeUnless { missed.alternatives == null || it.key in state.alerted }
    }
    val due = mutableListOf<TripAlert>()
    val phase = progress.phase
    if (state.warning == TripWarning.LIKELY_MISSED_CHANGE) {
        upcomingChange(phase)?.let { index ->
            val part = parts[index]
            due += TripAlert(
                "missed-$index", text(R.string.trip_alert_change_missed),
                text(
                    R.string.trip_alert_change_missed_body, part.routeShortName ?: text(R.string.trip_the_next_vehicle),
                    part.startStopName ?: text(R.string.trip_the_stop), clock(part.startDeparture.expected(delays[index])),
                ),
            )
        }
    }
    if (state.warning == TripWarning.MAY_MISS) {
        boarding(phase)?.let { index ->
            due += TripAlert(
                "hurry-$index", text(R.string.trip_alert_may_miss, parts[index].routeShortName ?: text(R.string.trip_the_next_vehicle_lowercase)),
                state.detail,
            )
        }
    }
    when (phase) {
        is TripPhase.Change -> due += TripAlert(
            "change-${phase.part}", parts[phase.part].startStopCode?.let { text(R.string.trip_alert_change_platform, it) } ?: state.title,
            state.detail,
        )
        is TripPhase.Ride -> if (progress.stopsLeft?.let { it <= 1 } ?: (seconds(now, state.time) <= 120)) {
            due += TripAlert("off-${phase.part}", state.title, state.detail)
        }
        else -> Unit
    }
    return due.firstOrNull { it.key !in state.alerted }
}

/** The change ahead: the vehicle after the current or awaited one. */
private fun Journey.upcomingChange(phase: TripPhase): Int? = when (phase) {
    is TripPhase.Board -> nextTransit(phase.part)
    is TripPhase.Ride -> nextTransit(phase.part)
    is TripPhase.Change -> phase.part
    is TripPhase.Walk, TripPhase.Arrived -> null
}

/** The vehicle to walk to and board. */
private fun boarding(phase: TripPhase): Int? = when (phase) {
    is TripPhase.Board -> phase.part
    is TripPhase.Change -> phase.part
    else -> null
}

private fun Journey.nextTransit(index: Int): Int? = parts.indices.firstOrNull { it > index && !parts[it].isWalk }

/**
 * Near a step the alerts and missed rides are timed by, when the service keeps the device awake for its refreshes:
 * 3 minutes before a vehicle leaves or arrives, and the first 6 minutes of a ride, while a missed boarding shows.
 */
fun Journey.isNearAlert(progress: TripProgress, state: TripState, now: Instant): Boolean {
    if (state.step == TripStep.MISSED) return false
    val untilStep = seconds(now, state.time)
    return when (val phase = progress.phase) {
        is TripPhase.Board, is TripPhase.Change -> untilStep <= 180
        is TripPhase.Ride -> untilStep <= 180 || seconds(parts[phase.part].startDeparture, now) <= 360
        else -> false
    }
}

/** The step row the traveller is at: the walk or change to the next vehicle until it leaves, the ride, then the final walk. */
fun TripProgress.step(journey: Journey): JourneyStep? {
    val steps = journey.steps
    return when (phase) {
        is TripPhase.Board, is TripPhase.Change -> {
            val ride = steps.indexOf(JourneyStep.Ride(boarding(phase) ?: return null))
            if (ride < 0) null else steps[max(ride - 1, 0)]
        }
        is TripPhase.Ride -> JourneyStep.Ride(phase.part)
        is TripPhase.Walk -> steps.lastOrNull()
        TripPhase.Arrived -> null
    }
}

/** The last arrival, the last vehicle's search-time delay included. */
val Journey.expectedArrival: Instant
    get() = parts.lastOrNull()?.endArrival?.expected(parts.lastOrNull { !it.isWalk }?.delaySeconds) ?: Instant.MAX

/** The lines of the rides from part [index] on. */
fun Journey.lines(index: Int = 0): List<String?> = parts.drop(index).filter { !it.isWalk }.map { it.routeShortName }

/**
 * [found] (from where the traveller is, soonest first) whose first vehicle has not left, sorted out against this trip's
 * rides from the missed [index] on: the next two on its lines, then three others.
 */
fun Journey.alternatives(found: List<Journey>, index: Int, now: Instant): TripAlternatives {
    val upcoming = found.filter { journey ->
        val ride = journey.parts.firstOrNull { !it.isWalk } ?: return@filter false
        ride.startDeparture.expected(ride.delaySeconds) >= now
    }
    val lines = lines(index)
    return TripAlternatives(
        sameRoute = upcoming.filter { it.lines() == lines }.take(2),
        others = upcoming.filter { it.lines() != lines }.sortedBy { it.expectedArrival }.take(3),
    )
}

/** With the stations' positions where it has none: offline results and trips saved by older versions. */
fun Journey.positioned(stops: List<Stop>): Journey = copy(
    parts = parts.map { part ->
        part.copy(
            startStopGps = part.startStopGps ?: stops.stop(part.startStationId, part.startStopName, part.startStopCode)?.gps,
            endStopGps = part.endStopGps ?: stops.stop(part.endStationId, part.endStopName, part.endStopCode)?.gps,
        )
    }
)

/** Where a location lies along a ride's stops. */
data class RidePlacement(
    /** Each stop's metres along the straight lines between the stops. */
    val stops: List<Double>,
    /** Metres along them to their point nearest the location. */
    val along: Double,
    /** That point as a stop index with the fraction on to the next stop. */
    val stop: Double,
    /** Within 250 m of the line (a quarter of the gap between far stops) plus the location's accuracy: the streets curve between stops. */
    val onLine: Boolean,
) {
    /** Metres along to [stop], a stop index with the fraction on to the next stop. */
    fun meters(stop: Double): Double {
        val lower = min(max(stop.toInt(), 0), stops.size - 2)
        return stops[lower] + (stop - lower) * (stops[lower + 1] - stops[lower])
    }
}

/**
 * Where [fix] lies along the leg's stops; where the line passes it more than once, nearest [stop] (a stop index with the
 * fraction on to the next). Null while a stop's position is unknown.
 */
fun Part.placement(fix: TripFix, stop: Double): RidePlacement? {
    // The location is the origin.
    val points = legStops.mapNotNull { it.gps?.meters(fix.gps) }
    if (points.size != legStops.size || points.size <= 1) return null
    val stops = mutableListOf(0.0)
    var bestOnLine = false
    var bestRank = Double.MAX_VALUE
    var bestAlong = 0.0
    var bestStop = 0.0
    for (index in 1 until points.size) {
        val (ax, ay) = points[index - 1]
        val (bx, by) = points[index]
        val dx = bx - ax
        val dy = by - ay
        val length = hypot(dx, dy)
        val t = if (length > 0) (-(ax * dx + ay * dy) / (length * length)).coerceIn(0.0, 1.0) else 0.0
        val off = hypot(ax + t * dx, ay + t * dy)
        val tolerance = max(250.0, length / 4) + fix.accuracy
        val at = index - 1 + t
        val onLine = off <= tolerance
        // On the line, the point nearest the vehicle; else the one nearest the line.
        val rank = if (onLine) abs(at - stop) else off - tolerance
        if (index == 1 || onLine && !bestOnLine || onLine == bestOnLine && rank < bestRank) {
            bestOnLine = onLine
            bestRank = rank
            bestAlong = stops[index - 1] + t * length
            bestStop = at
        }
        stops += stops[index - 1] + length
    }
    return RidePlacement(stops, bestAlong, bestStop, bestOnLine)
}

/** Metres east and north of [origin] on a plane, true to well under 1% across a city. */
fun StopGps.meters(origin: StopGps): Pair<Double, Double> {
    val perDegree = 111_195.0
    return (lon - origin.lon) * perDegree * cos(origin.lat * Math.PI / 180) to (lat - origin.lat) * perDegree
}

fun StopGps.distance(other: StopGps): Double {
    val (x, y) = meters(other)
    return hypot(x, y)
}

/** "80 m", "350 m", "1.2 km". */
private fun distanceText(meters: Double): TripText =
    if (meters < 950) text(R.string.trip_meters, (meters / 10).roundToInt() * 10)
    else text(R.string.trip_kilometers, String.format(Locale.ROOT, "%.1f", meters / 1000))

/** Live feeds drop the "Bratislava, " prefix of regional stop names. */
private fun sameStop(a: String, b: String) = a.replace("Bratislava, ", "") == b.replace("Bratislava, ", "")

/** The countdown from [now] as the board shows it: "4 min", "<1 min", "now", or the clock time from an hour ahead. */
private class Countdown(date: Instant, now: Instant) {
    val short: TripText
    val minimal: TripText
    val phrase: TripText

    init {
        val seconds = (Duration.between(now, date).toMillis() / 1000).toInt()
        when {
            seconds >= 3600 -> {
                short = TripText.Raw(clock(date))
                minimal = text(R.string.trip_countdown_hours_minimal, seconds / 3600)
                phrase = text(R.string.trip_countdown_at, short)
            }
            seconds >= 60 -> {
                short = text(R.string.trip_countdown_minutes, seconds / 60)
                minimal = text(R.string.trip_countdown_minutes_minimal, seconds / 60)
                phrase = text(R.string.trip_countdown_in, short)
            }
            seconds > 0 -> {
                short = text(R.string.trip_countdown_under_minute)
                minimal = text(R.string.trip_countdown_under_minute_minimal)
                phrase = text(R.string.trip_countdown_in, short)
            }
            else -> {
                short = text(R.string.trip_countdown_now)
                minimal = short
                phrase = short
            }
        }
    }
}
