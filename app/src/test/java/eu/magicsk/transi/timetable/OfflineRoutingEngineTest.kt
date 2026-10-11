package eu.magicsk.transi.timetable

import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OfflineRoutingEngineTest {
    // Stops share a place unless given one.
    private fun stop(name: String, lat: Double = 48.15, lon: Double = 17.11) = OfflineStop(name, null, null, StopGps(lon = lon, lat = lat))
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
    fun aNameFindsEveryPlatformOfItsStationElseTheStopsAroundWhereItWasPicked() {
        val stops = listOf(
            stop("Hodžovo nám."), stop("Hodžovo nám.", 48.1485), stop("Bratislava, Hodžovo nám."), stop("Bratislava, AS"),
            stop("Autobusová stanica"), stop("Rovinka, Majerská", 48.0921, 17.2459), stop("Majerská", 48.1518, 17.2004),
            stop("Zamajerské"),
        )
        // The stops 0 … 4 and 7 stand within 500 m of here (1 is 167 m away); the Majerská ones are kilometres off.
        val here = StopGps(lon = 17.11, lat = 48.15)
        assertEquals(setOf(0, 1, 2), stopsNamed(stops, "Hodžovo nám.", here))
        assertEquals(setOf(0, 1, 2), stopsNamed(stops, "Bratislava, Hodžovo nám.", here))
        assertEquals(setOf(3, 4), stopsNamed(stops, "Autobusová stanica", here))
        // A station name is found wherever it was picked, as the stops list's coordinates can be a kilometre off.
        assertEquals(setOf(5), stopsNamed(stops, "Rovinka, Majerská", here))
        assertEquals(setOf(6), stopsNamed(stops, "Majerská", here))
        // No station of that name: the stops around containing it, not those of the Majerská stations in two towns.
        assertEquals(setOf(7), stopsNamed(stops, "majer", here))
        // Else every stop around, as for Suché mýto, which the timetables lack.
        assertEquals(setOf(0, 1, 2, 3, 4, 7), stopsNamed(stops, "Suché mýto", here))
        assertEquals(setOf(5), stopsNamed(stops, "Suché mýto", StopGps(lon = 17.2459, lat = 48.0941)))
        // Nothing around: the online planner answers.
        assertEquals(emptySet<Int>(), stopsNamed(stops, "Nowhere", StopGps(lon = 17.3, lat = 48.3)))
        assertEquals(emptySet<Int>(), stopsNamed(stops, " ", here))
    }

    @Test
    fun sameNamedStopsOfAnotherTownAreNoStationToWalkWithin() {
        // 0 Alpha, 1 Stromová and 2 Dunajská Lužná, Stromová 15.9 km apart, 3 Delta.
        val stops = listOf(
            stop("Alpha"), stop("Stromová", 48.1638, 17.0981), stop("Dunajská Lužná, Stromová", 48.0809, 17.2718), stop("Delta"),
        )
        val trips = listOf(trip("1", 0 to at(8, 0), 2 to at(8, 10)), trip("2", 1 to at(8, 12), 3 to at(8, 20)))
        val here = StopGps(lon = 17.11, lat = 48.15)
        assertEquals(setOf(1), stopsNamed(stops, "Stromová", here))
        assertEquals(setOf(2), stopsNamed(stops, "Dunajská Lužná, Stromová", here))
        assertEquals(emptyList<List<Leg>>(), runBlocking { searchJourneys(stops, trips, setOf(0), setOf(3), at(7, 55), false, 6, true) })
    }

    @Test
    fun aChangeArrivingSoonerIsFoundBesideTheDirectRideAndDominatedJourneysAreDropped() {
        // 5 is another platform of Alpha; the feeder from it leaves first but its journey arrives later than the change.
        val stops = stops + stop("Alpha")
        val feeder = trip("4", 5 to at(7, 58), 1 to at(8, 6))
        val slow = trip("5", 1 to at(8, 10), 4 to at(8, 30))
        val change = listOf(Leg.Ride(0, 0, 2), Leg.Walk(2, 3, at(8, 10)), Leg.Ride(1, 0, 1))
        val found = runBlocking {
            searchJourneys(stops, listOf(toGamma, fromGamma, direct, feeder, slow), setOf(0, 5), setOf(4), at(7, 55), false, 6, true)
        }
        assertEquals(listOf(listOf(Leg.Ride(2, 0, 1)), change), found)
        // With a single ride allowed, only the direct one.
        assertEquals(listOf(listOf(Leg.Ride(2, 0, 1))), search(listOf(toGamma, fromGamma, direct), at(7, 55), maxLegs = 1))
    }

    @Test
    fun aJourneyWithMoreRidesNeedsToArriveAMinuteSooner() {
        // Borská → Saleziáni: the direct 9 arrives at 8:26:05, a change at Gamma seconds sooner looks the same but worse.
        val direct = trip("9", 0 to at(8, 0) + 45, 4 to at(8, 26) + 5)
        val toGamma = trip("1", 0 to at(8, 0) + 45, 2 to at(8, 10))
        val secondsSooner = trip("2", 3 to at(8, 12), 4 to at(8, 25) + 55)
        val minuteSooner = trip("2", 3 to at(8, 12), 4 to at(8, 25) + 5)
        assertEquals(listOf(listOf(Leg.Ride(0, 0, 1))), search(listOf(direct, toGamma, secondsSooner), at(7, 55)))
        val change = listOf(Leg.Ride(1, 0, 1), Leg.Walk(2, 3, at(8, 10)), Leg.Ride(2, 0, 1))
        assertEquals(listOf(listOf(Leg.Ride(0, 0, 1)), change), search(listOf(direct, toGamma, minuteSooner), at(7, 55)))
    }

    @Test
    fun journeysAreComparedToTheShownMinute() {
        // Leaving 30 s later and arriving 40 s later is the same 8:00 → 8:21 again.
        val first = trip("3", 0 to at(8, 0), 4 to at(8, 21))
        val second = trip("4", 0 to at(8, 0) + 30, 4 to at(8, 21) + 40)
        assertEquals(listOf(listOf(Leg.Ride(0, 0, 1))), search(listOf(first, second), at(7, 55)))
        // Arriving by 9:00 (times negated in that search): 8:59:30 direct is no worse than a change arriving 8:59:00.
        val directIn = trip("3", 0 to at(8, 30), 4 to at(8, 59) + 30)
        val toGamma = trip("1", 0 to at(8, 30), 2 to at(8, 40))
        val changeIn = trip("2", 3 to at(8, 45), 4 to at(8, 59))
        assertEquals(listOf(listOf(Leg.Ride(0, 0, 1))), search(listOf(directIn, toGamma, changeIn), at(9, 0), arrival = true))
    }

    @Test
    fun aRideWithinOneStationIsNoLeg() {
        // 5 is another platform of Alpha, 6 of Delta: like 69 from one Avion Shop. Park platform to another.
        val stops = stops + listOf(stop("Alpha"), stop("Delta"))
        val toPlatform = trip("69", 0 to at(8, 0), 5 to at(8, 0))
        val fromPlatform = trip("96", 5 to at(8, 5), 4 to at(8, 20))
        assertEquals(
            emptyList<List<Leg>>(),
            runBlocking { searchJourneys(stops, listOf(toPlatform, fromPlatform), setOf(0), setOf(4), at(7, 55), false, 6, true) },
        )
        val toDelta = trip("96", 0 to at(8, 0), 6 to at(8, 20))
        val withinDelta = trip("69", 6 to at(8, 21), 4 to at(8, 21))
        assertEquals(
            emptyList<List<Leg>>(),
            runBlocking { searchJourneys(stops, listOf(toDelta, withinDelta), setOf(0), setOf(4), at(8, 30), true, 6, true) },
        )
    }

    @Test
    fun noJourneyLeavesADestination() {
        // Delta is among the stops around the origin too: riding away from it and back is no journey.
        val away = trip("84", 4 to at(8, 0), 1 to at(8, 5))
        val back = trip("84", 1 to at(8, 6), 4 to at(8, 10))
        val found = runBlocking { searchJourneys(stops, listOf(away, back, direct), setOf(0, 4), setOf(4), at(7, 55), false, 6, true) }
        assertEquals(listOf(listOf(Leg.Ride(2, 0, 1))), found)
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
