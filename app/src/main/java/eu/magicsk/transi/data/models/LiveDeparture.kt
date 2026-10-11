package eu.magicsk.transi.data.models

/**
 * iOS matchesLiveActivityReference: this row is the departure [followed] (its row when following started) on a later
 * board: the same stop, then the same row, or the same line at the same scheduled time from the same platform (the
 * board's [isSameDeparture] rule), since a timetable row and the regional data that replaces it share no id, and the
 * regional feed knows no platform (-1).
 */
fun MHDTableData.matches(followed: MHDTableData): Boolean =
    stopId == followed.stopId && (
        id == followed.id || line == followed.line && departureTimeCP == followed.departureTimeCP &&
            (platform == followed.platform || platform == -1 || followed.platform == -1)
        )

/**
 * A followed departure ends once its board, whole for its platform, no longer lists it ([row] is null), as on iOS; only
 * after the board listed it ([seen]), since a regional row may show before the regional feed answered.
 * ponytail: also 30 min after it was last expected, so a board that never drops it or a connection that never comes
 * back cannot keep the service running; iOS relies on the system ending Live Activities instead.
 */
fun departureEnded(row: MHDTableData?, seen: Boolean, hasWholeBoard: Boolean, expectedDeparture: Long, now: Long): Boolean =
    (row == null && seen && hasWholeBoard) || now - expectedDeparture > 30 * 60_000L

/** The texts the alert rule compares between two updates of a followed departure (iOS Connection fields). */
data class LiveState(val remaining: String, val delay: String, val lastStop: String)

/** The Settings "Notification triggers". */
data class AlertTriggers(val timeChange: Boolean, val delayChange: Boolean, val positionChange: Boolean)

/**
 * iOS updateActivity's alert rule, "only alert on real vehicle progress": the update to [new], [secondsLeft] before the
 * departure, alerts when that is under [thresholdSeconds], a chosen trigger's text changed since [old], and the vehicle
 * progressed: another last stop, or fewer minutes left than at the last alert (a stuck vehicle bouncing 2→1→2 min
 * alerts once). Returns the minutes to keep as the next [lastAlertMinutes] (0 for "<1 min", -1 for "now"), or null
 * for a silent update.
 */
fun liveAlertMinutes(
    old: LiveState?,
    new: LiveState,
    secondsLeft: Long,
    lastAlertMinutes: Int?,
    triggers: AlertTriggers,
    thresholdSeconds: Int,
): Int? {
    val isNew = (triggers.timeChange && old?.remaining != new.remaining) ||
        (triggers.delayChange && old?.delay != new.delay) ||
        (triggers.positionChange && old?.lastStop != new.lastStop)
    val minutes = if (secondsLeft > 0) (secondsLeft / 60).toInt() else -1
    val progress = old?.lastStop != new.lastStop || minutes < (lastAlertMinutes ?: Int.MAX_VALUE)
    return minutes.takeIf { secondsLeft < thresholdSeconds && isNew && progress }
}
