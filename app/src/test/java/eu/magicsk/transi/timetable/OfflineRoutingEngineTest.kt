package eu.magicsk.transi.timetable

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OfflineRoutingEngineTest {
    private fun stop(name: String) = OfflineStop(name, null, null, null)
    private fun at(hours: Int, minutes: Int) = hours * 3600 + minutes * 60
    private fun trip(line: String, vararg calls: Pair<Int, Int>) =
        OfflineTrip(line, 3, null, calls.map { it.first }.toIntArray(), calls.map { it.second }.toIntArray())

    // 0 Alpha, 1 Beta, 2 and 3 two platforms of Gamma, 4 Delta.
    private val stops = listOf(stop("Bratislava, Alpha"), stop("Beta"), stop("Gamma"), stop("Bratislava, Gamma"), stop("Delta"))
    private val toGamma = trip("1", 0 to at(8, 0), 1 to at(8, 5), 2 to at(8, 10))
    private val fromGamma = trip("2", 3 to at(8, 12), 4 to at(8, 20))
    private val laterToGamma = trip("1", 0 to at(8, 30), 1 to at(8, 35), 2 to at(8, 40))
    private val laterFromGamma = trip("2", 3 to at(8, 45), 4 to at(8, 55))
    private val direct = trip("3", 0 to at(8, 2), 4 to at(8, 40))

    private fun search(
        trips: List<OfflineTrip>,
        time: Int,
        arrival: Boolean = false,
        maxLegs: Int = 6,
        walkTransfers: Boolean = true,
    ) = runBlocking { searchJourneys(stops, trips, setOf(0), setOf(4), time, arrival, maxLegs, walkTransfers) }

    @Test
    fun stationsDropTheTownAndGroupTheBusStation() {
        assertEquals("Hodžovo nám.", stationName("Bratislava, Hodžovo nám."))
        assertEquals("Majerská", stationName("Rovinka, Majerská"))
        assertEquals("Zvonica", stationName("Dunajská Lužná, Zvonica"))
        assertEquals("Škola", stationName("Miloslavov, Škola"))
        assertEquals("Senec, SNP", stationName("Senec, SNP"))
        assertEquals("Autobusová stanica", stationName("Bratislava, AS"))
        assertEquals("Trnava, AS", stationName("Trnava, AS"))
    }

    @Test
    fun aNameFindsEveryPlatformOfItsStationElseStopsContainingIt() {
        val stops = listOf(
            stop("Hodžovo nám."), stop("Hodžovo nám."), stop("Bratislava, Hodžovo nám."), stop("Bratislava, AS"),
            stop("Autobusová stanica"), stop("Rovinka, Majerská"), stop("Majerská"), stop("Zamajerské"),
        )
        assertEquals(setOf(0, 1, 2), stopsNamed(stops, "Hodžovo nám."))
        assertEquals(setOf(3, 4), stopsNamed(stops, "Autobusová stanica"))
        assertEquals(setOf(5, 6), stopsNamed(stops, "Rovinka, Majerská"))
        assertEquals(setOf(5, 6, 7), stopsNamed(stops, "majer"))
        assertEquals(emptySet<Int>(), stopsNamed(stops, "Nowhere"))
        assertEquals(emptySet<Int>(), stopsNamed(stops, " "))
    }

    @Test
    fun theFewestRidesWinEvenWhenAChangeArrivesSooner() {
        assertEquals(listOf(listOf(Leg.Ride(2, 0, 1))), search(listOf(toGamma, fromGamma, direct), at(7, 55)))
    }

    @Test
    fun changesWalkAMinuteToAnotherPlatformOfTheStation() {
        val change = listOf(Leg.Ride(0, 0, 2), Leg.Walk(2, 3, at(8, 10)), Leg.Ride(1, 0, 1))
        val laterChange = listOf(Leg.Ride(2, 0, 2), Leg.Walk(2, 3, at(8, 40)), Leg.Ride(3, 0, 1))
        val trips = listOf(toGamma, fromGamma, laterToGamma, laterFromGamma)
        assertEquals(listOf(change, laterChange), search(trips, at(7, 55)))
        assertEquals(listOf(laterChange), search(trips, at(8, 1)))
    }

    @Test
    fun aJourneyLeavingLaterWithFewerRidesReplacesOneArrivingAsLate() {
        // 0 and 5 are platforms of Alpha, 1 and 6 of Beta.
        val stops = stops + listOf(stop("Alpha"), stop("Bratislava, Beta"))
        val shuttle = trip("S", 0 to at(8, 0), 1 to at(8, 2))
        val tram = trip("T", 5 to at(8, 1), 6 to at(8, 4), 4 to at(8, 20))
        val found = runBlocking { searchJourneys(stops, listOf(shuttle, tram), setOf(0, 5), setOf(4), at(7, 55), false, 6, true) }
        assertEquals(listOf(listOf(Leg.Ride(1, 0, 2))), found)
    }

    @Test
    fun maxTransfersAndWalkingLimitTheRides() {
        val trips = listOf(toGamma, fromGamma)
        assertEquals(emptyList<List<Leg>>(), search(trips, at(7, 55), maxLegs = 1))
        assertEquals(emptyList<List<Leg>>(), search(trips, at(7, 55), walkTransfers = false))
        assertEquals(1, search(trips, at(7, 55), maxLegs = 2).size)
    }

    @Test
    fun arrivalsAreTheLatestJourneysArrivingInTime() {
        val trips = listOf(toGamma, fromGamma, laterToGamma, laterFromGamma)
        // Walks start as late as the next ride allows.
        val change = listOf(Leg.Ride(0, 0, 2), Leg.Walk(2, 3, at(8, 11)), Leg.Ride(1, 0, 1))
        val laterChange = listOf(Leg.Ride(2, 0, 2), Leg.Walk(2, 3, at(8, 44)), Leg.Ride(3, 0, 1))
        assertEquals(listOf(laterChange, change), search(trips, at(9, 0), arrival = true))
        assertEquals(listOf(change), search(trips, at(8, 50), arrival = true))
        assertEquals(emptyList<List<Leg>>(), search(trips, at(8, 15), arrival = true))
    }

    @Test
    fun serviceTimesAreBratislavaWallClockAlsoOnDstDays() {
        assertEquals(LocalDate.of(2026, 10, 12) to at(8, 30), serviceTime(Instant.parse("2026-10-12T06:30:00Z")))
        // 25- and 23-hour days: 10:00 is still 36000 s, not the 39600 or 32400 s elapsed since midnight.
        assertEquals(LocalDate.of(2026, 10, 25) to at(10, 0), serviceTime(Instant.parse("2026-10-25T09:00:00Z")))
        assertEquals(LocalDate.of(2026, 3, 29) to at(10, 0), serviceTime(Instant.parse("2026-03-29T08:00:00Z")))
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), wallClock(LocalDate.of(2026, 10, 25), at(10, 0)))
        assertEquals(Instant.parse("2026-03-29T08:00:00Z"), wallClock(LocalDate.of(2026, 3, 29), at(10, 0)))
        // Past midnight of the service day, and before it (the previous day's trips).
        assertEquals(Instant.parse("2026-10-12T23:05:00Z"), wallClock(LocalDate.of(2026, 10, 12), at(25, 5)))
        assertEquals(Instant.parse("2026-10-11T21:30:00Z"), wallClock(LocalDate.of(2026, 10, 12), -at(0, 30)))
    }
}
