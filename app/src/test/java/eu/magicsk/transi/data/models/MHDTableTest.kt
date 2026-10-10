package eu.magicsk.transi.data.models

import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.PlatformLabel
import eu.magicsk.transi.data.remote.responses.PlatformLabels
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RegionalDeparture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ports the iOS scripts/check-table-dedup.sh fixture and the board's text and colour rules. */
class MHDTableTest {
    private fun departure(
        line: String, platform: Int, cp: String, type: String = "online", id: String = "$line:$platform:$cp",
        expected: Long = 0,
    ): MHDTableData {
        val (hours, minutes) = cp.split(":").map { it.toLong() }
        return MHDTableData(
            id, 1, line, platform, 82, "1:2552", "Čiližská", 1_791_000_000 + hours * 3600 + minutes * 60,
            expected, 0, type, 12, 12, "Cintorín Vrakuňa", false
        )
    }

    @Test
    fun matchesSocketAndRegionalTrips() {
        // Socket rounds casCP to the minute; regional schedules are whole minutes.
        val cases = listOf(
            Triple(departure("70", 715, "18:38"), departure("70", 715, "18:37"), true),
            Triple(departure("42", 715, "11:26"), departure("42", 715, "11:23"), true),
            Triple(departure("740", 715, "11:35"), departure("740", -1, "11:35"), true),
            Triple(departure("42", 716, "11:26"), departure("42", 715, "11:26"), false),
            Triple(departure("9", 955, "11:24"), departure("9", 955, "11:20"), false),
            Triple(departure("71", 715, "11:27"), departure("72", 715, "11:27"), false),
        )
        for ((socket, regional, expected) in cases) {
            assertEquals("${socket.id} vs ${regional.id}", expected, isSameDeparture(socket, regional))
            assertEquals("${socket.id} vs ${regional.id} must be symmetric", expected, isSameDeparture(regional, socket))
        }
        // Tram 3 runs every 2 minutes: a timetable-only row must not take over the previous tram's live data.
        val untrackedTram = departure("3", 262, "07:02")
        assertFalse(isSameDeparture(untrackedTram, departure("3", 262, "07:00"), 0))
        assertTrue(isSameDeparture(untrackedTram, departure("3", 262, "07:02"), 0))
    }

    @Test
    fun recognisesRegionalTerminus() {
        assertTrue(terminates("Patrónka", "Patrónka"))
        assertTrue(terminates("Bratislava, Patrónka", "Patrónka"))
        assertTrue(terminates("Bratislava, AS", "Autobusová stanica"))
        assertFalse(terminates("Nem. Bory ► Podvornice", "Patrónka"))
        assertFalse(terminates("Hlavná stanica", "Prievozská"))
        assertFalse(terminates("Bratislava, AS", "Prievozská"))
        assertFalse(terminates("", null))
    }

    @Test
    fun mergesBothFeeds() {
        val now = 1_791_000_000L + 11 * 3600 + 30 * 60
        val scheduledSocket = departure("740", 715, "11:35", type = "cp", expected = 2_000)
        val liveSocket = departure("42", 715, "11:26", expected = 1_000)
        val socket = listOf(scheduledSocket, liveSocket)
        val liveRegional = departure("740", -1, "11:35", id = "regional-740", expected = 3_000)
        val duplicateOfLive = departure("42", -1, "11:24", id = "regional-42", expected = 500)
        val delayedPast = departure("801", -1, "11:20", id = "regional-801", expected = 4_000)
        val futureOnlyRegional = departure("802", -1, "11:40", id = "regional-802", expected = 5_000)

        val merged = mergeDepartures(socket, listOf(liveRegional, duplicateOfLive, delayedPast, futureOnlyRegional), now)

        // The scheduled socket row takes the live regional data and keeps its own platform; the live socket row
        // hides its regional duplicate; only regional rows already past their schedule are added; sorted by time.
        assertEquals(listOf(liveSocket.id, "regional-740", "regional-801"), merged.map { it.id })
        assertEquals(715, merged[1].platform)
    }

    @Test
    fun replacesOnlyPlatformsInTheMessage() {
        val kept = departure("1", 1, "10:00")
        val replaced = departure("2", 2, "10:00")
        val cleared = departure("3", 3, "10:00")
        val incoming = departure("4", 2, "10:05")
        val rows = replacePlatforms(listOf(kept, replaced, cleared), mapOf(82 to mapOf(2 to listOf(incoming), 3 to emptyList())), 82)
        assertEquals(listOf(kept, incoming), rows)
    }

    @Test
    fun ignoresTabsOfThePreviousStop() {
        val current = departure("1", 1, "10:00")
        val stale = departure("2", 1, "10:05").copy(stopId = 81)
        val message = mapOf(81 to mapOf(1 to listOf(stale), 2 to emptyList()))
        assertEquals(listOf(current), replacePlatforms(listOf(current), message, 82))
        assertEquals(emptyList<MHDTableData>(), replacePlatforms(emptyList(), message, 82))
    }

    @Test
    fun mapsRegionalDepartures() {
        val zone = ZoneId.of("Europe/Bratislava")
        val now = ZonedDateTime.of(2026, 10, 10, 11, 0, 0, 0, zone)
        val labels = PlatformLabels().apply { add(PlatformLabel("240", "A")) }
        val stop = Stop(94, 1386, "Hronská", "Bratislava", "bus", 0, 0.0, 0.0, labels)
        fun regional(departure: Int, delay: Int?, headsign: String = "Senec", code: String? = "A") =
            RegionalDeparture(7, headsign, "540", departure, 900, code, delay, null, null)

        val rows = regionalDepartures(
            listOf(
                regional(11 * 60 + 10, 150),
                regional(11 * 60 + 20, null),
                regional(11 * 60, 0),
                regional(11 * 60 + 30, 0, headsign = "Bratislava, Hronská"),
                regional(11 * 60 + 40, -60, code = "Z"),
            ),
            stop, now
        )

        assertEquals(2, rows.size)
        val delayed = rows[0]
        assertEquals(240, delayed.platform)
        assertEquals(2, delayed.delay)
        assertEquals("online", delayed.type)
        assertEquals(now.plusMinutes(10).toEpochSecond(), delayed.departureTimeCP)
        assertEquals(now.plusMinutes(12).toInstant().toEpochMilli(), delayed.departureTime)
        assertEquals("7:900:${delayed.departureTimeCP}", delayed.id)
        assertEquals("none", delayed.lastStopName)
        assertEquals(-1, rows[1].platform)
        assertEquals(-1, rows[1].delay)
    }

    @Test
    fun remainingTimeFollowsIos() {
        val now = 1_000_000L
        assertNull(remainingMinutes(now, now))
        assertNull(remainingMinutes(now - 5_000, now))
        assertNull(remainingMinutes(now + 999, now))
        assertEquals(0, remainingMinutes(now + 1_000, now))
        assertEquals(0, remainingMinutes(now + 59_999, now))
        assertEquals(1, remainingMinutes(now + 60_000, now))
        assertEquals(59, remainingMinutes(now + 3_599_000, now))
        assertEquals(60, remainingMinutes(now + 3_600_000, now))
    }

    @Test
    fun delayTextAndColourFollowIos() {
        assertEquals(R.string.table_delay_offline to 0, delayText(5, "cp"))
        assertEquals(R.string.delay to 3, delayText(3, "online"))
        assertEquals(R.string.inAdvance to 2, delayText(-2, "online"))
        assertEquals(R.string.table_no_delay to 0, delayText(0, "online"))

        assertEquals(R.color.table_grey, delayColor(0, "cp"))
        assertEquals(R.color.table_green, delayColor(0, "online"))
        assertEquals(R.color.table_green, delayColor(1, "online"))
        assertEquals(R.color.table_orange, delayColor(2, "online"))
        assertEquals(R.color.table_orange, delayColor(3, "online"))
        assertEquals(R.color.table_red, delayColor(4, "online"))
        assertEquals(R.color.table_red, delayColor(999, "online"))
        assertEquals(R.color.table_purple, delayColor(1000, "online"))
        assertEquals(R.color.table_purple, delayColor(-1, "online"))
    }
}
