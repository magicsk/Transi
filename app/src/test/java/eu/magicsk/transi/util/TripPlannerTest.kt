package eu.magicsk.transi.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.magicsk.transi.adapters.tripPlannerPages
import eu.magicsk.transi.data.models.ArrivalDeparture
import eu.magicsk.transi.data.models.BRATISLAVA
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.PartStop
import eu.magicsk.transi.data.models.RecentTripSearch
import eu.magicsk.transi.data.models.canRestoreTrip
import eu.magicsk.transi.data.models.decodeRecentSearches
import eu.magicsk.transi.data.models.encodeRecentSearches
import eu.magicsk.transi.data.models.plannerGson
import eu.magicsk.transi.data.models.remember
import eu.magicsk.transi.data.models.restorable
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RaptorResponse
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.view_models.TripPlannerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime

/** The planner's port of the iOS search, mapping, paging and recents, against the iOS fixture. */
class TripPlannerTest {
    private val fixture: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("trip-detail.json")).readText()
    ).asJsonObject
    // The iOS fixture leaves out fields the Android stops list always has.
    private val stops: List<Stop> = Gson().fromJson(
        fixture.getAsJsonArray("stops").onEach { it.asJsonObject.addProperty("city", ""); it.asJsonObject.addProperty("type", "bus") },
        Array<Stop>::class.java,
    ).toList()
    private val cepo = fixture.get("cepo").toString()

    private fun local(time: String): Instant = LocalDateTime.parse(time.replace(' ', 'T')).atZone(BRATISLAVA).toInstant()

    private fun ride(start: String, end: String, line: String, from: String, to: String) =
        Part(from, to, null, null, Instant.parse(start), Instant.parse(end), 3, null, line)

    private fun walk(start: String, end: String, from: String, to: String) =
        Part(from, to, null, null, Instant.parse(start), Instant.parse(end), 64, null, null)

    private fun journey(id: String, vararg parts: Part) = Journey(id, parts.toList(), null)

    private fun stop(id: Int) = Stop(id, id + 1000, "Stop $id", "", "bus", 0, 48.1, 17.1, null)

    private fun search(from: Int, to: Int) =
        RecentTripSearch(stop(from), stop(to), ArrivalDeparture.DEPARTURE, Instant.parse("2026-10-05T06:00:00Z"), false)

    @Test
    fun raptorJourneysMapAsOnIos() {
        val response = plannerGson.fromJson(fixture.get("raptor"), RaptorResponse::class.java)
        val journeys = mapRaptorJourneys(response.journey.orEmpty())

        assertTrue(journeys.all { it.isRaptor && !it.isImhd })
        assertEquals(listOf(806, 804), journeys.map { it.ticketId })
        assertEquals(listOf("1955", "1953"), journeys[0].zones)
        val bus72 = journeys[0].parts[0]
        assertEquals(listOf(13517, 53701, 900, 945, 1386, 1404), listOf(bus72.tripId, bus72.tripRouteId, bus72.startStopId, bus72.endStopId, bus72.startStationId, bus72.endStationId))
        assertEquals(StopGps(lon = 17.208740234375, lat = 48.1357383728027), bus72.startStopGps)
        assertEquals(Instant.parse("2026-10-05T06:00:00Z"), bus72.startDeparture)
        assertEquals(Instant.parse("2026-10-05T06:21:00Z"), bus72.endArrival)
        assertEquals(listOf("72", "Rajská", "A", "A", "Hronská"), listOf(bus72.routeShortName, bus72.tripHeadsign, bus72.startStopCode, bus72.endStopCode, bus72.startStopName))
        assertEquals(listOf("1955", "1953"), bus72.zones)
        assertEquals(804, bus72.ticketId)
        assertNull(bus72.delaySeconds)
        assertNull(bus72.stops)
        val walk = journeys[0].parts[1]
        assertTrue(walk.isWalk)
        assertNull(walk.tripId)
        assertEquals(63, journeys[1].parts[0].delaySeconds)

        val odd = plannerGson.fromJson(
            """{"journey":[{"parts":[{"start_stop_code":"","route_type":3,"start_departure":"2026-10-05T06:00:00Z","end_arrival":"2026-10-05T06:10:00.000Z"}]},{"zones":[1]}]}""",
            RaptorResponse::class.java,
        )
        val mapped = mapRaptorJourneys(odd.journey.orEmpty())
        assertEquals("Journeys without parts are dropped", 1, mapped.size)
        assertNull("An empty platform letter is no platform", mapped[0].parts[0].startStopCode)
        assertEquals(Instant.parse("2026-10-05T06:10:00Z"), mapped[0].parts[0].endArrival)
    }

    @Test
    fun cepoJourneysMapAsOnIos() {
        val journey = requireNotNull(mapCepoJourneys(cepo, stops)).single()

        assertTrue(journey.isImhd && !journey.isRaptor)
        assertEquals(listOf("72", "42", "80"), journey.parts.map { it.routeShortName })
        assertEquals("Fare zones are flattened", listOf("100", "101"), journey.zones)
        val trolley72 = journey.parts[0]
        assertEquals(1, trolley72.routeType)
        assertEquals(true, trolley72.lowFloor)
        assertEquals(1386, trolley72.startStationId)
        assertEquals(StopGps(lon = 17.208740234375, lat = 48.1357383728027), trolley72.startStopGps)
        assertEquals("Staré Mesto, Rajská", trolley72.tripHeadsign)
        assertEquals(local("2026-10-05 08:00"), trolley72.startDeparture)
        assertEquals(local("2026-10-05 08:18"), trolley72.endArrival)
        assertEquals(16, trolley72.stops?.size)
        assertEquals(
            PartStop("Hronská", "A", local("2026-10-05 08:00"), StopGps(lon = 17.208740234375, lat = 48.1357383728027), "101", true, 94),
            trolley72.stops?.first(),
        )
        assertEquals(local("2026-10-05 08:18"), trolley72.stops?.last()?.time)
        val kollarovo = journey.parts[2].stops?.first()
        assertEquals(local("2026-10-05 08:31"), kollarovo?.time)
        assertEquals(false, kollarovo?.isRequestStop)
        assertTrue("Every stop resolves", journey.parts.all { part -> part.stops.orEmpty().all { it.stopId != null && it.gps != null } })

        fun firstStopId(stops: List<Stop>) = mapCepoJourneys(cepo, stops)?.single()?.parts?.first()?.stops?.first()?.stopId
        assertEquals("Stop pole join", 94, firstStopId(stops.map { it.copy(name = "Renamed") }))
        assertEquals("Name fallback", 94, firstStopId(stops.map { it.copy(platform_labels = null) }))
    }

    @Test
    fun cepoWalksSparseArraysAndZoneShapes() {
        val json = """
            {"journeys": {"3": {
              "departure": {"date": "2026-03-29 01:50:00.000000"}, "arrival": {"date": "2026-03-29 03:20:00.000000"},
              "linesMapping": [0, 1, 2, 7], "lines": [[["ba"], "N33"], [["ba"], 21]], "fare_zones": ["100", "101"],
              "parts": [
                {"type": "🚶", "departure": {"date": "2026-03-29 01:50:00.000000"}, "arrival": {"date": "2026-03-29 01:55:00.000000"},
                 "stops": [{"name": "Hronská", "label": "A"}, {"name": "Hronská", "label": ""}]},
                {"type": "bus", "destination": "Centrum",
                 "departure": {"date": "2026-03-29 01:58:00.000000"}, "arrival": {"date": "2026-03-29 03:05:00"},
                 "stops": [{"name": "Hronská", "label": "B", "fare_zones": "101", "departure": {"date": "2026-03-29 01:58:00.000000"}},
                           {"name": "Zochova", "label": "B", "fare_zones": ["100"], "arrival": {"date": "2026-03-29 03:05:00"}}]},
                {"type": "🚶", "departure": {"date": "2026-03-29 03:05:00.000000"}, "arrival": {"date": "2026-03-29 03:20:00.000000"},
                 "stops": [{"name": "Zochova"}, {"name": "Kollárovo nám."}]}
              ]}}}
        """.trimIndent()
        val journey = requireNotNull(mapCepoJourneys(json, stops)).single()

        assertEquals(listOf(64, 1, 64), journey.parts.map { it.routeType })
        assertEquals("Lines by linesMapping, else Err", listOf("N33", "Err", "Err"), journey.parts.map { it.routeShortName })
        assertEquals(listOf("100", "101"), journey.zones)
        val walk = journey.parts[0]
        assertEquals("A walk's missing end platform comes from the next part", "B", walk.endStopCode)
        assertNull(walk.stops)
        assertNull(walk.lowFloor)
        val bus = journey.parts[1]
        assertEquals("01:58 CET", Instant.parse("2026-03-29T00:58:00Z"), bus.startDeparture)
        assertEquals("03:05 CEST", Instant.parse("2026-03-29T01:05:00Z"), bus.endArrival)
        assertEquals(listOf("101", "100"), bus.stops?.map { it.zone })
        assertEquals(listOf(Instant.parse("2026-03-29T00:58:00Z"), Instant.parse("2026-03-29T01:05:00Z")), bus.stops?.map { it.time })
        assertNull("The last walk keeps no platform", journey.parts[2].endStopCode)

        assertNull(mapCepoJourneys("<html>", stops))
        assertNull(mapCepoJourneys("[]", stops))
        assertEquals(emptyList<Journey>(), mapCepoJourneys("{}", stops))
    }

    @Test
    fun journeysAreTheSameRidesAsOnIos() {
        val ride = ride("2026-10-05T06:00:00Z", "2026-10-05T06:21:00Z", "72", "Hronská", "Rajská")
        val raptor = journey("r-1", walk("2026-10-05T05:50:00Z", "2026-10-05T05:58:00Z", "A", "Hronská"), ride)
        val imhd = journey(
            "i-1",
            walk("2026-10-05T05:40:00Z", "2026-10-05T05:45:00Z", "B", "Hronská"),
            ride.copy(startDeparture = Instant.parse("2026-10-05T06:00:40Z"), routeType = 1, startStopCode = "A", delaySeconds = 60),
        )

        assertEquals("Ids, walks and seconds don't count", raptor.sameRidesKey, imhd.sameRidesKey)
        assertNotEquals("Data class equality for the list diffs", raptor, imhd)
        assertNotEquals(raptor.sameRidesKey, journey("r-2", raptor.parts[0], ride.copy(startDeparture = Instant.parse("2026-10-05T06:01:00Z"))).sameRidesKey)
        assertNotEquals(raptor.sameRidesKey, journey("r-3", raptor.parts[0], ride.copy(routeShortName = "73")).sameRidesKey)
        assertNotEquals(raptor.sameRidesKey, journey("r-4", raptor.parts[0], ride.copy(endStopName = "Zochova")).sameRidesKey)
        assertNotEquals(raptor.sameRidesKey, journey("r-5", ride).sameRidesKey)
    }

    @Test
    fun mergeKeepsTheFirstOfEachJourneySoonestFirst() {
        val late = journey("r-late", ride("2026-10-05T07:00:00Z", "2026-10-05T07:20:00Z", "72", "A", "B"))
        val lateAgain = journey("i-late", ride("2026-10-05T07:00:30Z", "2026-10-05T07:20:00Z", "72", "A", "B"))
        val early = journey("i-early", ride("2026-10-05T06:00:00Z", "2026-10-05T06:20:00Z", "42", "A", "B"))

        assertEquals(listOf("i-early", "r-late"), mergeJourneys(listOf(late), listOf(lateAgain, early)).map { it.id })
        assertEquals(listOf("i-early", "i-late"), mergeJourneys(emptyList(), listOf(lateAgain, late, early)).map { it.id })
    }

    @Test
    fun pagingDatesMoveAsOnIos() {
        val start = Instant.parse("2026-10-05T06:00:00Z")
        val dates = PagingDates(start, start)
        val imhd = listOf(
            journey("i-1", ride("2026-10-05T06:40:00Z", "2026-10-05T07:00:00Z", "72", "A", "B")),
            journey("i-2", ride("2026-10-05T06:10:00Z", "2026-10-05T06:30:00Z", "42", "A", "B")),
        )

        assertEquals(
            "R +2 h; I a minute after its last journey, as sent",
            PagingDates(start.plusSeconds(7200), Instant.parse("2026-10-05T06:11:00Z")),
            nextPagingDates(FetchSource.INITIAL, dates, raptorAnswered = true, imhdAnswered = true, imhdJourneys = imhd),
        )
        assertEquals(
            "I found nothing: +1 h; a failed R stays",
            PagingDates(start, start.plusSeconds(3600)),
            nextPagingDates(FetchSource.INITIAL, dates, raptorAnswered = false, imhdAnswered = true, imhdJourneys = emptyList()),
        )
        assertEquals(dates, nextPagingDates(FetchSource.INITIAL, dates, raptorAnswered = false, imhdAnswered = false, imhdJourneys = emptyList()))
        assertEquals(
            PagingDates(start.plusSeconds(7200), start),
            nextPagingDates(FetchSource.MORE_RAPTOR, dates, raptorAnswered = false, imhdAnswered = false, imhdJourneys = emptyList()),
        )
        assertEquals(
            PagingDates(start, start.plusSeconds(3600)),
            nextPagingDates(FetchSource.MORE_IMHD, dates, raptorAnswered = false, imhdAnswered = false, imhdJourneys = emptyList()),
        )
        assertEquals(
            "Cached journeys page on from a minute after their last of each source",
            PagingDates(Instant.parse("2026-10-05T07:01:00Z"), Instant.parse("2026-10-05T06:41:00Z")),
            pagingDatesFor(mergeJourneys(imhd, listOf(journey("r-1", ride("2026-10-05T07:00:00Z", "2026-10-05T07:20:00Z", "73", "A", "B"))))),
        )
        assertEquals(PagingDates(null, null), pagingDatesFor(emptyList()))
    }

    @Test
    fun raptorBodyIsTheIosOne() {
        val date = Instant.parse("2026-10-05T06:00:00.750Z")
        fun json(value: Any) = JsonParser.parseString(Gson().toJson(value))

        val departure = raptorRequest(1386, 1456, date, ArrivalDeparture.DEPARTURE, 15, 3)
        assertEquals(
            JsonParser.parseString("""{"org_id":120,"max_walk_duration":15,"max_transfers":3,"search_to_hours":2,"search_from":"2026-10-05T06:00:00Z","from_station_id":[1386],"to_station_id":[1456]}"""),
            json(departure),
        )
        assertEquals(
            JsonParser.parseString("""{"org_id":120,"max_walk_duration":0,"max_transfers":10,"search_from_hours":2,"search_to":"2026-10-05T06:00:00Z","from_station_id":[1],"to_station_id":[2]}"""),
            json(raptorRequest(1, 2, date, ArrivalDeparture.ARRIVAL, 0, 10)),
        )
        val retry = departure.widened()
        assertEquals(8, retry?.search_to_hours)
        assertNull(retry?.search_from_hours)
        assertNull("Retried once", retry?.widened())
    }

    @Test
    fun cepoDateAndClockAreBratislavaWallClock() {
        assertEquals("2026-10-06", cepoDate(Instant.parse("2026-10-05T22:30:00Z")))
        assertEquals("00:30", clock(Instant.parse("2026-10-05T22:30:00Z")))
        assertEquals("2027-01-01", cepoDate(Instant.parse("2026-12-31T23:30:00Z")))
        assertEquals("00:30", clock(Instant.parse("2026-12-31T23:30:00Z")))
        // Summer time starts 2026-03-29 at 01:00 UTC and ends 2026-10-25 at 01:00 UTC.
        assertEquals("01:59", clock(Instant.parse("2026-03-29T00:59:00Z")))
        assertEquals("03:00", clock(Instant.parse("2026-03-29T01:00:00Z")))
        assertEquals("02:30", clock(Instant.parse("2026-10-25T00:30:00Z")))
        assertEquals("02:30", clock(Instant.parse("2026-10-25T01:30:00Z")))
        assertEquals("08:05", clock(Instant.parse("2026-01-15T07:05:59Z")))
    }

    @Test
    fun recentsAreDedupedNewestFirstAndCapped() {
        var recents = emptyList<RecentTripSearch>()
        for (id in 1..12) recents = recents.remember(search(id, id + 1))
        assertEquals(10, recents.size)
        assertEquals("12:13", recents.first().id)
        assertEquals("3:4", recents.last().id)

        recents = recents.remember(search(5, 6).copy(arrivalDeparture = ArrivalDeparture.ARRIVAL))
        assertEquals(10, recents.size)
        assertEquals("5:6", recents.first().id)
        assertEquals(ArrivalDeparture.ARRIVAL, recents.first().arrivalDeparture)
        assertEquals(1, recents.count { it.id == "5:6" })
    }

    @Test
    fun aNewSearchIsSelectedBeforeTheFullRecentsMoveUp() {
        fun currentIndex(state: TripPlannerState) = tripPlannerPages(state).indexOfFirst { it.isCurrent }
        var recents = emptyList<RecentTripSearch>()
        for (id in 1..10) recents = recents.remember(search(id, id + 1))
        // Restored on launch: the newest recent, without journeys, on the last page.
        val restored = TripPlannerState(from = stop(10), to = stop(11), customDate = true, recents = recents)
        assertEquals(9, currentIndex(restored))

        val picked = restored.copy(to = stop(20))
        assertEquals("The new search's own page, not the newest recent's", 10, currentIndex(picked))
        val searching = picked.copy(loading = true, recents = recents.remember(requireNotNull(picked.criteria)))
        assertEquals("The oldest recent dropped: the page moves, so the pager follows", 9, currentIndex(searching))
        assertEquals("10:20", tripPlannerPages(searching)[9].searchId)
    }

    @Test
    fun savedTripsExpireAsOnIos() {
        val now = Instant.parse("2026-10-11T12:00:00Z")
        assertTrue(canRestoreTrip(null, -1, now))
        assertTrue(canRestoreTrip(now.minusSeconds(400L * 86_400), -1, now))
        assertFalse("Until restart", canRestoreTrip(now, 0, now))
        assertTrue(canRestoreTrip(now.minusSeconds(3599), 1, now))
        assertFalse(canRestoreTrip(now.minusSeconds(3600), 1, now))
        assertFalse(canRestoreTrip(null, 24, now))

        val trip = listOf(journey("r-1", ride("2026-10-11T10:00:00Z", "2026-10-11T10:20:00Z", "72", "A", "B")))
        val cached = listOf(search(1, 2).copy(trip = trip, tripSavedAt = now.minusSeconds(2 * 3600)))
        assertNull(cached.restorable(1, now).single().trip)
        assertEquals(trip, cached.restorable(6, now).single().trip)
    }

    @Test
    fun recentsSurviveJson() {
        val journeys = mapRaptorJourneys(plannerGson.fromJson(fixture.get("raptor"), RaptorResponse::class.java).journey.orEmpty()) +
            requireNotNull(mapCepoJourneys(cepo, stops))
        val searches = listOf(
            search(1, 2).copy(trip = journeys, tripSavedAt = Instant.parse("2026-10-05T06:01:02.5Z")),
            search(3, 4).copy(arrivalDeparture = ArrivalDeparture.ARRIVAL, arrivalDepartureCustomDate = true),
        )
        val json = encodeRecentSearches(searches)
        val decoded = decodeRecentSearches(json)

        assertEquals(searches, decoded)
        assertEquals(journeys.map { it.id }, decoded[0].trip?.map { it.id })
        assertEquals(journeys.map { it.parts }, decoded[0].trip?.map { it.parts })
        assertEquals(journeys.map { it.ticketId to it.zones }, decoded[0].trip?.map { it.ticketId to it.zones })
        assertTrue(json.contains("\"arrivalDeparture\":\"arrival\""))
        assertEquals(emptyList<RecentTripSearch>(), decodeRecentSearches("not json"))
        assertEquals(emptyList<RecentTripSearch>(), decodeRecentSearches(null))
    }
}
