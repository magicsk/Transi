package eu.magicsk.transi.data.models

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.magicsk.transi.R
import eu.magicsk.transi.adapters.TripDetailRow
import eu.magicsk.transi.adapters.tripDetailRows
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RaptorResponse
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.util.mapCepoJourneys
import eu.magicsk.transi.util.mapRaptorJourneys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** The trip detail's port of iOS scripts/check-trip-detail.sh (ride shapes included), against the iOS fixture. */
class TripDetailTest {
    private val fixture: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("trip-detail.json")).readText()
    ).asJsonObject
    // The iOS fixture leaves out fields the Android stops list always has.
    private val stops: List<Stop> = Gson().fromJson(
        fixture.getAsJsonArray("stops").onEach { it.asJsonObject.addProperty("city", ""); it.asJsonObject.addProperty("type", "bus") },
        Array<Stop>::class.java,
    ).toList()
    private val trip: BApiTrip = plannerGson.fromJson(fixture.get("trip"), BApiTrip::class.java)
    private val rJourneys = mapRaptorJourneys(plannerGson.fromJson(fixture.get("raptor"), RaptorResponse::class.java).journey.orEmpty())
    private val iJourney = requireNotNull(mapCepoJourneys(fixture.get("cepo").toString(), stops)).single()
    private val bus72 = rJourneys[0].parts[0]
    private val walkChange = rJourneys[0]

    private fun local(time: String): Instant = LocalDateTime.parse(time.replace(' ', 'T')).atZone(BRATISLAVA).toInstant()

    private fun part(start: Instant, end: Instant = start, routeType: Int? = null, startStopId: Int? = null, endStopId: Int? = null) =
        Part(null, null, null, null, start, end, routeType, null, null, startStopId = startStopId, endStopId = endStopId)

    private fun stopTime(id: Int, minutes: Int, code: String? = "A") =
        BApiStopTime(id, null, null, code, "S$id", minutes, minutes, "100")

    private fun leg(from: Int, to: Int, at: String) = part(local(at), startStopId = from, endStopId = to)

    @Test
    fun legStopsAreSlicedFromTheRunAsOnIos() {
        val leg72 = requireNotNull(trip.partStops(bus72, stops))
        assertEquals(18, leg72.size)
        assertEquals("Hronská", leg72.first().name)
        assertEquals("Rajská", leg72.last().name)
        assertEquals("Bratislava minutes match the R-API UTC times", bus72.startDeparture, leg72.first().time)
        assertEquals(bus72.endArrival, leg72.last().time)
        val rajska = stops.first { it.station_id == 1404 }
        assertEquals("imhd stop ids, falling back to the station without the platform letter", listOf(94, rajska.id), listOf(leg72.first().stopId, leg72.last().stopId))
        assertTrue("Platform GPS and letters", leg72.all { it.gps != null && it.platform != null })
        assertEquals("Cintorín Vrakuňa enters zone 100", listOf(ZoneChange(4, "101", "100")), leg72.zoneChanges)
        assertNull("Legs without B-API stop ids have nothing to slice", trip.partStops(part(Instant.now()), emptyList()))

        // A loop line serves stop 1 twice.
        val loop = BApiTrip(1, null, listOf(stopTime(1, 600), stopTime(2, 602), stopTime(3, 604), stopTime(1, 606), stopTime(4, 608)))
        val loopCases = listOf(
            leg(3, 1, "2026-10-05 10:04") to listOf("S3", "S1"),
            leg(1, 4, "2026-10-05 10:00") to listOf("S1", "S2", "S3", "S1", "S4"),
            leg(1, 4, "2026-10-05 10:06") to listOf("S1", "S4"),
            leg(1, 2, "2026-10-05 10:06") to null,
            leg(4, 1, "2026-10-05 10:08") to null,
        )
        loopCases.forEach { (leg, expected) -> assertEquals("Loop leg $expected", expected, loop.partStops(leg, emptyList())?.map { it.name }) }

        // Train stops come with empty platform letters, which map to none.
        val train = BApiTrip(3, null, listOf(stopTime(1, 754, ""), stopTime(2, 769, null)))
        assertEquals(listOf(null, null), train.partStops(leg(1, 2, "2026-10-05 12:34"), emptyList())?.map { it.platform })
    }

    @Test
    fun serviceDayMinutesAreBratislavaWallClockTimes() {
        val october5 = LocalDate.parse("2026-10-05")
        assertEquals("08:00 CEST", Instant.parse("2026-10-05T06:00:00Z"), serviceDayTime(october5, 480))
        assertEquals("1450 is 00:10 next day", Instant.parse("2026-10-05T22:10:00Z"), serviceDayTime(october5, 1450))
        assertEquals("05:00 CET on the day summer time ends", Instant.parse("2026-10-25T04:00:00Z"), serviceDayTime(LocalDate.parse("2026-10-25"), 300))
        val night = BApiTrip(2, 30, listOf(stopTime(7, 1430), stopTime(8, 1450), stopTime(9, 1455)))
        assertEquals(
            "Night trip from 23:50",
            listOf(local("2026-10-05 23:50"), local("2026-10-06 00:10"), local("2026-10-06 00:15")),
            night.partStops(leg(7, 9, "2026-10-05 23:50"), emptyList())?.map { it.time },
        )
        assertEquals(
            "Boarding after midnight keeps the service day",
            listOf(local("2026-10-06 00:10"), local("2026-10-06 00:15")),
            night.partStops(leg(8, 9, "2026-10-06 00:10"), emptyList())?.map { it.time },
        )
    }

    @Test
    fun zoneBordersSkipStopsWithoutAZone() {
        fun zoned(vararg zones: String?) = zones.map { PartStop("", null, Instant.EPOCH, null, it, false, null) }
        assertEquals(emptyList<ZoneChange>(), zoned("100", "100").zoneChanges)
        assertEquals(
            listOf(ZoneChange(2, "101", "100"), ZoneChange(4, "100", "101")),
            zoned("101", null, "100", "100", "101").zoneChanges,
        )
    }

    @Test
    fun transferBuffersCountTheDelaysAsShown() {
        // 72 to Rajská, 10-minute walk, 31 leaves when the walk ends.
        assertEquals(mapOf(2 to TransferBuffer(0)), walkChange.transferBuffers())
        val scheduled = walkChange.transferBuffers().getValue(2)
        assertTrue(scheduled.isTight && !scheduled.isLikelyMissed)
        assertTrue("One spare minute is tight", walkChange.transferBuffers(mapOf(2 to 60)).getValue(2).isTight)
        assertFalse("Two spare minutes are not", walkChange.transferBuffers(mapOf(2 to 120)).getValue(2).isTight)
        val late72 = walkChange.transferBuffers(mapOf(0 to 63)).getValue(2)
        assertTrue("A 72 shown +1 min misses the 31 by 1 min", late72.minutes == -1 && late72.isLikelyMissed)
        val late31 = walkChange.transferBuffers(mapOf(0 to 63, 2 to 120)).getValue(2)
        assertTrue("A late 31 still waits", late31.minutes == 1 && late31.isTight && !late31.isLikelyMissed)
        // 20 s reads "on time", 50 s "+1 min", 90 s "+2 min", so "on time" never sits next to "likely missed".
        listOf(20 to 0, 29 to 0, 30 to -1, 50 to -1, 90 to -2, -50 to 1).forEach { (seconds, minutes) ->
            val buffer = walkChange.transferBuffers(mapOf(0 to seconds)).getValue(2)
            assertEquals("$seconds s late 72", minutes, buffer.minutes)
            assertEquals(-delayMinutes(seconds), buffer.minutes)
            assertTrue(buffer.isTight && buffer.isLikelyMissed == (minutes < 0))
        }
        // Same-stop changes without a walk part: 08:18 → 08:21 and 08:26:30 → 08:31.
        assertEquals(mapOf(1 to TransferBuffer(3), 2 to TransferBuffer(4)), iJourney.transferBuffers())
        assertTrue("Roomy changes", iJourney.transferBuffers().values.none { it.isTight })
    }

    @Test
    fun stepsAndSummaryCountsAsOnIos() {
        assertEquals(listOf(JourneyStep.Ride(0), JourneyStep.Walk(listOf(1), 2), JourneyStep.Ride(2)), walkChange.steps)
        assertEquals(listOf(2, 10), listOf(walkChange.rideCount, walkChange.walkMinutes))
        assertEquals("R-API summary before the stops load", emptyList<String>(), walkChange.zoneNames)
        val loaded = walkChange.copy(parts = walkChange.parts.mapIndexed { i, p -> if (i == 0) p.copy(stops = trip.partStops(bus72, stops)) else p })
        assertEquals("Zone names from the loaded stops", listOf("101", "100"), loaded.zoneNames)
        assertEquals(
            "Same-stop changes",
            listOf(JourneyStep.Ride(0), JourneyStep.Change(1), JourneyStep.Ride(1), JourneyStep.Change(2), JourneyStep.Ride(2)),
            iJourney.steps,
        )
        assertEquals(listOf(3, 0), listOf(iJourney.rideCount, iJourney.walkMinutes))
        assertEquals(listOf("101", "100"), iJourney.zoneNames)
        assertEquals("Leg minutes match their shown times", listOf(18, 5, 2), iJourney.parts.map { it.minutes })

        val noon = local("2026-10-05 12:00")
        fun walk(minutes: Long) = part(noon, noon.plusSeconds(minutes * 60), ROUTE_TYPE_WALK)
        val ride = part(noon, routeType = 3)
        val stepCases = listOf(
            listOf(walk(4), ride, walk(2)) to listOf(JourneyStep.Walk(listOf(0), 1), JourneyStep.Ride(1), JourneyStep.Walk(listOf(2), null)),
            listOf(ride, walk(1), walk(3), ride) to listOf(JourneyStep.Ride(0), JourneyStep.Walk(listOf(1, 2), 3), JourneyStep.Ride(3)),
            listOf(ride) to listOf(JourneyStep.Ride(0)),
            listOf(walk(7)) to listOf(JourneyStep.Walk(listOf(0), null)),
            emptyList<Part>() to emptyList(),
        )
        stepCases.forEach { (parts, expected) -> assertEquals("Steps $expected", expected, Journey("steps", parts, null).steps) }
        assertEquals("Walk minutes add up", 6, Journey("walks", listOf(walk(4), ride, walk(2)), null).walkMinutes)
    }

    @Test
    fun liveBoardMatchingAsOnIos() {
        fun board(line: String, time: String, platform: Int = 240, busID: String = "1:1", headsign: String = "Rajská") = MHDTableData(
            "$line $platform $time $busID $headsign", 0, line, platform, 0, busID, headsign,
            local("2026-10-05 $time").epochSecond, 0, 0, "online", -1, -1, "", false,
        )
        val scheduled = local("2026-10-05 08:00")
        val part72 = Part(null, null, null, null, scheduled, scheduled, 50, "Rajská", "72")
        val cases = listOf(
            Triple("closest of two", listOf(board("72", "08:05"), board("72", "08:02")), Triple(240, null, board("72", "08:02"))),
            Triple("feeds 3 min apart", listOf(board("72", "07:57")), Triple(240, null, board("72", "07:57"))),
            Triple("over 3 min away", listOf(board("72", "08:04")), Triple(240, null, null)),
            Triple("other line", listOf(board("71", "08:00")), Triple(240, null, null)),
            Triple("other direction", listOf(board("72", "08:00", platform = 241)), Triple(240, null, null)),
            Triple("regional row", listOf(board("72", "08:01", platform = -1)), Triple(240, null, board("72", "08:01", platform = -1))),
            Triple("unknown platform", listOf(board("72", "08:00", platform = 241)), Triple(null, null, board("72", "08:00", platform = 241))),
            Triple("headsign tiebreak", listOf(board("72", "08:00", headsign = "Trnávka"), board("72", "08:00")), Triple(240, null, board("72", "08:00"))),
            Triple("followed vehicle", listOf(board("72", "08:00"), board("72", "08:02", busID = "1:7")), Triple(240, "1:7", board("72", "08:02", busID = "1:7"))),
            Triple("followed vehicle gone", listOf(board("72", "08:00"), board("72", "08:01", busID = "1:9")), Triple(240, "1:7", null)),
        )
        cases.forEach { (name, connections, expectation) ->
            val (platform, busID, expected) = expectation
            assertEquals(name, expected, part72.liveConnection(connections, platform, scheduled, busID))
        }
    }

    @Test
    fun liveWindowRunsFromAnHourBeforeUntilTheExpectedArrival() {
        val liveLeg = part(local("2026-10-05 08:00"), local("2026-10-05 08:20"))
        val cases = listOf(
            Triple("06:59", null, false), Triple("07:01", null, true), Triple("08:10", null, true), Triple("08:20", null, false),
            Triple("06:59", 480, false), Triple("08:20", 480, true), Triple("08:25", 480, true), Triple("08:29", 480, false),
            Triple("08:19", -120, true), Triple("08:20", 0, false),
        )
        cases.forEach { (time, delay, expected) ->
            assertEquals("Live at $time, ${delay ?: 0} s late", expected, liveLeg.isLive(local("2026-10-05 $time"), delay))
        }
    }

    @Test
    fun delaysMoveTimesByTheMinutesTheyReadAs() {
        val boarding = local("2026-10-05 12:19")
        assertEquals(boarding, boarding.expected(null))
        assertEquals(local("2026-10-05 12:22"), boarding.expected(180))
        assertEquals(local("2026-10-05 12:17"), boarding.expected(-120))
        listOf(20 to "12:19", 29 to "12:19", 30 to "12:20", 50 to "12:20", 90 to "12:21", -50 to "12:18", -30 to "12:18").forEach { (seconds, time) ->
            assertEquals("$seconds s late shows $time", local("2026-10-05 $time"), boarding.expected(seconds))
        }
    }

    @Test
    fun delayPriorityAndArrival() {
        val leg = part(local("2026-10-05 08:00"), local("2026-10-05 08:20"), 3).copy(delaySeconds = 30)
        val state = TripDetailState(Journey("live", listOf(leg), null), now = local("2026-10-05 07:30"))
        assertEquals("The search's delay", 30, state.knownDelaySeconds(0))
        val bApi = state.copy(tripDelays = mapOf(0 to 120))
        assertEquals("The B-API's delay before the search's", 120, bApi.knownDelaySeconds(0))
        assertNull("A B-API run not on the road has none", state.copy(tripDelays = mapOf(0 to null)).knownDelaySeconds(0))
        val board = MHDTableData("x", 0, "72", 1, 0, "1:1", "", 0, 0, 3, "online", -1, -1, "", false)
        assertEquals("The board's online delay first", 180, bApi.copy(liveConnections = mapOf(0 to board)).knownDelaySeconds(0))
        assertEquals("A timetable row has none to give", 120, bApi.copy(liveConnections = mapOf(0 to board.copy(type = "cp"))).knownDelaySeconds(0))
        assertNull("Shown only while live", state.copy(now = local("2026-10-05 06:30")).delaySeconds(0))
        assertEquals(mapOf(0 to 30), state.delays)

        val walk = part(local("2026-10-05 08:20"), local("2026-10-05 08:25"), ROUTE_TYPE_WALK)
        val journey = Journey("arrival", listOf(leg, walk), null)
        assertFalse("Walking after the ride", journey.hasArrived(local("2026-10-05 08:24"), emptyMap()))
        assertTrue(journey.hasArrived(local("2026-10-05 08:25"), emptyMap()))
        assertFalse("The last vehicle's delay moves the walk", journey.hasArrived(local("2026-10-05 08:25"), mapOf(0 to 60)))
    }

    @Test
    fun rideShapesJoinWithStraightLines() {
        val shapeStops = listOf(17.1, 17.11, 17.12, 17.13).map { StopGps(lon = it, lat = 48.1) }
        fun point(gps: StopGps) = listOf(gps.lat, gps.lon)
        val bend = listOf(48.11, 17.115)
        val curve = listOf(48.105, 17.125)
        val nearStop1 = listOf(48.1001, 17.1101)
        val decoded = Gson().fromJson("""{"segments":[null,[[48.1,17.11],[48.11,17.115],[48.1,17.12]],null]}""", TimetableShape::class.java)
        val cases = listOf(
            Triple("all unknown", listOf(null, null, null), shapeStops),
            Triple("too few segments", listOf(listOf(point(shapeStops[0]), bend, point(shapeStops[1])), null), shapeStops),
            Triple("too many segments", listOf(null, null, null, null), shapeStops),
            Triple("one point or malformed points", listOf(listOf(bend), listOf(listOf(48.1), listOf(17.1, 48.1, 3.0)), null), shapeStops),
            Triple(
                "known between unknown, sharing the stops", decoded.segments.orEmpty(),
                listOf(shapeStops[0], shapeStops[1], StopGps(lon = 17.115, lat = 48.11), shapeStops[2], shapeStops[3]),
            ),
            Triple(
                "ending near the stops", listOf(listOf(point(shapeStops[0]), nearStop1), listOf(nearStop1, curve, point(shapeStops[2])), null),
                listOf(shapeStops[0], StopGps(lon = 17.1101, lat = 48.1001), StopGps(lon = 17.125, lat = 48.105), shapeStops[2], shapeStops[3]),
            ),
            Triple(
                "unknown after a segment ending off its stop", listOf(listOf(point(shapeStops[0]), nearStop1), null, null),
                listOf(shapeStops[0], StopGps(lon = 17.1101, lat = 48.1001), shapeStops[1], shapeStops[2], shapeStops[3]),
            ),
        )
        cases.forEach { (name, segments, expected) -> assertEquals("Shape: $name", expected, TimetableShape(segments).path(shapeStops)) }
        assertEquals("A single stop stays", listOf(shapeStops[0]), TimetableShape(emptyList()).path(listOf(shapeStops[0])))
        val example = StopGps(lon = 17.208740234375, lat = 48.1357383728027)
        assertEquals(
            "timetable/shape?line=N%2072%26&stops=48.13574,17.20874;-48.12346,17.10000",
            timetableShapeEndpoint("N 72&", listOf(example, StopGps(lon = 17.1, lat = -48.123456))),
        )
        assertTrue("A + in the line stays a +", timetableShapeEndpoint("S+", listOf(example, example)).contains("line=S%2B&"))
    }

    @Test
    fun sheetRowsFollowTheStepsAndStops() {
        val rows = tripDetailRows(TripDetailState(iJourney, expanded = setOf(0)), TicketCoverage.Unknown, notificationsOn = true)
        assertTrue(rows.first() is TripDetailRow.Summary)
        val timeline = rows.dropWhile { it != TripDetailRow.Header(R.string.trip_detail_stops) }.drop(1)
        val firstRide = timeline.takeWhile { it !is TripDetailRow.Alight }
        assertEquals(TripDetailRow.Collapse(0, "72", 14, 18, expanded = true), firstRide[1])
        assertEquals("The zone border before the stop in the new zone", ZoneChange(4, "101", "100"), (firstRide[5] as TripDetailRow.Zone).change)
        assertEquals(14, firstRide.count { it is TripDetailRow.Middle })
        assertEquals("Get off, get off, arrive", listOf(false, false, true), timeline.filterIsInstance<TripDetailRow.Alight>().map { it.isLast })
        assertEquals(2, timeline.count { it is TripDetailRow.Change })

        val loading = tripDetailRows(TripDetailState(walkChange, stopsState = mapOf(0 to StopsState.LOADING)), TicketCoverage.Unknown, notificationsOn = true)
        assertTrue(loading.contains(TripDetailRow.StopsLoading(0, "72")))
        val noon = local("2026-10-05 12:00")
        val endsOnFoot = Journey("foot", listOf(part(noon, noon.plusSeconds(600), 3), part(noon.plusSeconds(600), noon.plusSeconds(900), ROUTE_TYPE_WALK)), null)
        assertTrue("A walk ending the journey arrives", tripDetailRows(TripDetailState(endsOnFoot), TicketCoverage.Unknown, notificationsOn = true).last() is TripDetailRow.Arrive)
    }

    @Test
    fun ridesFocusAndShapeOnlyWithEveryStopPosition() {
        val parts = walkChange.parts
        assertEquals("A walk between rides runs from the ride's end to the next one's start", listOf(parts[0].endStopGps, parts[2].startStopGps), partPoints(parts, 1))
        assertEquals("A change: the last ride's end, the walk, the next ride's start",
            listOf(parts[0].endStopGps, parts[1].startStopGps, parts[1].endStopGps, parts[2].startStopGps),
            focusPoints(MapFocus.Change(2), parts))
        assertNull("No shape until the stops load", bus72.shapeEndpoint)
        val loaded = bus72.copy(stops = trip.partStops(bus72, stops))
        assertTrue(requireNotNull(loaded.shapeEndpoint).startsWith("timetable/shape?line=72&stops=48.13574,17.20874;"))
        assertEquals(18, partPoints(listOf(loaded), 0).size)
    }
}
