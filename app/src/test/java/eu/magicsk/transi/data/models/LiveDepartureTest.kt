package eu.magicsk.transi.data.models

import eu.magicsk.transi.repository.WholeBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Followed departures: iOS matching, when they end, and the alert rule (iOS scripts/check-live-activity-alerts.sh). */
class LiveDepartureTest {
    private fun row(id: String, line: String = "70", cp: Long = 1_791_000_000, stopId: Int = 82, platform: Int = 715) =
        MHDTableData(id, 1, line, platform, stopId, "1:2552", "Čiližská", cp, cp * 1000, 0, "online", 12, 12, "Trnavské mýto", false)

    @Test
    fun matchesTheSameRowOrTheSameLineAndScheduleFromThePlatform() {
        val followed = row("-123:715")
        assertTrue(row("-123:715", cp = 1_791_000_060).matches(followed))
        // A timetable row the regional feed replaced on the board, and back: other ids, same line and schedule.
        assertTrue(row("4242:82:1791000000", platform = -1).matches(followed))
        assertTrue(row("-123:715").matches(row("4242:82:1791000000", platform = -1)))
        assertFalse(row("-124:715", cp = 1_791_000_060).matches(followed))
        assertFalse(row("-124:715", line = "71").matches(followed))
        assertFalse(row("-123:715", stopId = 83).matches(followed))
        // The same line the other way at the same minute, from the opposite platform, is another departure.
        assertFalse(row("-124:716", platform = 716).matches(followed))
    }

    @Test
    fun countsAPlatformOnceTheSocketListedIt() {
        val board = WholeBoard()
        assertFalse("nothing before connecting", board.has(715, 60_000))
        board.onConnect(0)
        // The socket sends its board a few platforms per message: a later platform's departure is still coming.
        board.onSocketBoard(setOf(211, 212), 400)
        assertTrue(board.has(211, 401))
        assertFalse(board.has(715, 401))
        board.onSocketBoard(setOf(715), 405)
        assertTrue(board.has(715, 406))
        // Platforms the socket never lists (regional ones, -1 for unknown) count 10 s after its first message.
        assertFalse(board.has(-1, 10_399))
        assertTrue(board.has(-1, 10_400))
        board.onDisconnect()
        assertFalse("a lost connection counts nothing", board.has(715, 20_000))
        board.onSocketBoard(setOf(715), 20_001)
        assertFalse("nor a message that arrives after it", board.has(715, 20_002))
        board.onConnect(30_000)
        assertFalse("a new connection waits for its own board", board.has(715, 30_001))
    }

    @Test
    fun countsEverythingWhenTheSocketSendsNoBoard() {
        // Stops outside the city have only regional departures.
        val board = WholeBoard()
        board.onConnect(1_000)
        assertFalse(board.has(715, 10_999))
        assertTrue(board.has(715, 11_000))
    }

    @Test
    fun endsWhenTheWholeBoardMissesItOrLongAfterItLeft() {
        val expected = 1_791_000_000_000
        assertTrue(departureEnded(null, seen = true, hasWholeBoard = true, expected, expected))
        assertFalse("the regional feed alone never ends it", departureEnded(null, seen = true, hasWholeBoard = false, expected, expected))
        // A late regional bus is listed only by the regional feed, which may answer after the socket's first board.
        assertFalse("a row never seen on the board cannot leave it", departureEnded(null, seen = false, hasWholeBoard = true, expected, expected))
        assertTrue("never seen, it still ends 30 min on", departureEnded(null, seen = false, hasWholeBoard = true, expected, expected + 31 * 60_000))
        assertFalse(departureEnded(row("-123:715"), seen = true, hasWholeBoard = true, expected, expected + 29 * 60_000))
        assertTrue(departureEnded(row("-123:715"), seen = true, hasWholeBoard = false, expected, expected + 31 * 60_000))
    }

    @Test
    fun stuckVehicleAlertsOnlyOnProgress() {
        // Mirrors the script with default settings: 200 s threshold, every trigger on. The 10 s ticker updates on
        // text changes; the server pushes every 60 s.
        val triggers = AlertTriggers(timeChange = true, delayChange = true, positionChange = true)
        val now = 1_791_000_000_000
        fun remaining(secondsLeft: Int) = when (val minutes = remainingMinutes(now + secondsLeft * 1000L + 500, now)) {
            null -> "now"
            0 -> "<1 min"
            else -> "$minutes min"
        }
        fun state(secondsLeft: Int, lastStop: String) = LiveState(remaining(secondsLeft), "no delay", lastStop)

        var eta = 150
        var stop = "Trnavské mýto"
        var old = state(eta, stop)
        var lastAlertMinutes: Int? = null
        var alertsBefore = 0
        val alerts = mutableListOf<String>()
        for (t in 10..780 step 10) {
            val isPush = t % 60 == 0
            if (isPush && t < 600) {
                eta = t + 150 // stuck: every update slides the ETA a minute later
            } else if (t == 600) {
                eta = 750 // moving again: passed the next stop, steady ETA
                stop = "Račianske mýto"
            }
            val new = state(eta - t, stop)
            if (!isPush && new.remaining == old.remaining) continue
            val secondsLeft = (eta - t).toLong()
            // Without the progress check (no alert yet), every changed text under the threshold alerted.
            if (liveAlertMinutes(old, new, secondsLeft, null, triggers, 200) != null) alertsBefore++
            liveAlertMinutes(old, new, secondsLeft, lastAlertMinutes, triggers, 200)?.let { minutes ->
                lastAlertMinutes = minutes
                alerts += "${t}s ${new.remaining}"
            }
            old = new
        }

        assertTrue("Replay must reproduce the stuck-vehicle alert spam ($alertsBefore)", alertsBefore >= 20)
        assertEquals(listOf("40s 1 min", "600s 2 min", "640s 1 min", "700s <1 min", "750s now"), alerts)
    }

    @Test
    fun alertsOnlyForChosenTriggersUnderTheThreshold() {
        val old = LiveState("3 min", "no delay", "Trnavské mýto")
        val delayed = old.copy(delay = "1 min delay")
        val none = AlertTriggers(timeChange = false, delayChange = false, positionChange = false)
        assertEquals(2, liveAlertMinutes(old, delayed, 150, null, none.copy(delayChange = true), 200))
        assertNull(liveAlertMinutes(old, delayed, 150, null, none.copy(timeChange = true, positionChange = true), 200))
        assertNull("over the threshold", liveAlertMinutes(old, delayed, 200, null, none.copy(delayChange = true), 200))
        assertEquals(-1, liveAlertMinutes(old, old.copy(remaining = "now"), 0, 0, none.copy(timeChange = true), 200))
    }
}
