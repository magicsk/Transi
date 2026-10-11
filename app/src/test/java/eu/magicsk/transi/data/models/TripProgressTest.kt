package eu.magicsk.transi.data.models

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.RaptorResponse
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.util.mapCepoJourneys
import eu.magicsk.transi.util.mapRaptorJourneys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The followed trip's port of iOS scripts/check-trip-live-activity.sh, against the iOS fixture: the real legs of an R-API
 * journey (72 Hronská → Rajská with its 18 stops, a 10-minute walk, 31 Kollárovo nám. D → Zochova without its stops)
 * and an I-API one (72, 42 and 80 with same-platform changes). Texts resolve from strings_start_trip.xml.
 */
class TripProgressTest {
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
    private val walkChange = rJourneys[0].withParts { index, part -> if (index == 0) part.copy(stops = trip.partStops(part, stops)) else part }
    private val sameStopChanges = requireNotNull(mapCepoJourneys(fixture.get("cepo").toString(), stops)).single()
    private val finalWalk = walkChange.copy(parts = walkChange.parts.subList(0, 2))
    private val leg72 = walkChange.parts[0].legStops
    private val hronska = gps(leg72[0])
    private val rajska = gps(leg72[17])
    private val kollarovo = requireNotNull(walkChange.parts[2].startStopGps)

    private fun Journey.withParts(change: (Int, Part) -> Part) = copy(parts = parts.mapIndexed(change))

    private fun gps(stop: PartStop) = requireNotNull(stop.gps)

    private fun local(time: String): Instant =
        LocalDateTime.parse("2026-10-05T" + if (time.length == 5) "$time:00" else time).atZone(BRATISLAVA).toInstant()

    private fun at(
        time: String,
        journey: Journey = walkChange,
        live: Map<Int, Int> = emptyMap(),
        passedStops: Map<Int, Int> = emptyMap(),
        alerted: Set<String> = emptySet(),
        fix: TripFix? = null,
        missed: TripMissed? = null,
    ) = journey.update(local(time), journey.delays(live), passedStops, alerted, fix, missed)

    // Texts

    private val names: Map<Int, String> = (R.string::class.java.fields + R.plurals::class.java.fields).associate { it.getInt(null) to it.name }
    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/strings_start_trip.xml"))
        fun Element.value() = textContent.replace("\\'", "'")
        val stringNodes = document.getElementsByTagName("string")
        for (index in 0 until stringNodes.length) (stringNodes.item(index) as Element).let { strings[it.getAttribute("name")] = it.value() }
        val pluralNodes = document.getElementsByTagName("plurals")
        for (index in 0 until pluralNodes.length) {
            val plural = pluralNodes.item(index) as Element
            val items = plural.getElementsByTagName("item")
            plurals[plural.getAttribute("name")] = (0 until items.length).map { items.item(it) as Element }
                .associate { it.getAttribute("quantity") to it.value() }
        }
    }

    /** As on screen in English. */
    private fun shown(text: TripText): String = when (text) {
        is TripText.Res -> String.format(Locale.ROOT, strings.getValue(names.getValue(text.id)), *text.args.map { if (it is TripText) shown(it) else it }.toTypedArray())
        is TripText.Plural -> String.format(Locale.ROOT, plurals.getValue(names.getValue(text.id)).getValue(if (text.count == 1) "one" else "other"), text.count)
        is TripText.Raw -> text.text
    }

    private val TripState.titleText get() = shown(title)
    private val TripState.detailText get() = shown(detail)
    private val TripState.compactText get() = shown(compact)

    private fun alertOf(update: TripUpdate) = update.alert?.let { Triple(it.key, shown(it.title), shown(it.body)) }

    // Live boards

    /** The followed vehicle on a board: [late] seconds late, having left [lastStop] last, leaving at [leaves]. */
    private fun row(part: Part, busID: String = "1:2552", lastStop: String, leaves: Instant, late: Int = 0) = MHDTableData(
        "x", 0, part.routeShortName.orEmpty(), 0, 0, busID, "", leaves.epochSecond - late, leaves.toEpochMilli(), late / 60,
        "online", -1, -1, lastStop, false,
    )

    private class Replay(val steps: List<TripUpdate>, val alerts: List<TripAlert>, val live: TripLiveData)

    /**
     * Replays the trip against live boards: a board's first update comes before it loaded, then a stop's board lists the
     * vehicle [late] seconds late with the stop it left last until it leaves that stop, and the line's next vehicle 2
     * minutes behind it. From [unlistedFrom] no board lists the vehicle any more. Boards match like the service's: start
     * from the vehicle followed so far, else lock onto the first one live. Starts from [live] and [alerted], as restored.
     */
    private fun replayBoards(
        journey: Journey, late: Int, from: String, to: String, unlistedFrom: String? = null,
        live: TripLiveData = TripLiveData(), alerted: Set<String> = emptySet(),
    ): Replay {
        val data = live.copy()
        var shownAlerts = alerted
        val loaded = mutableSetOf<TripTrackerTarget>()
        val locked = mutableMapOf<TripTrackerTarget, String?>()
        val steps = mutableListOf<TripUpdate>()
        val alerts = mutableListOf<TripAlert>()
        var now = local(from)
        while (now <= local(to)) {
            val progress = journey.progress(now, journey.delays(data.delays), data.passedStops)
            for (target in journey.trackerTargets(progress)) {
                val part = journey.parts[target.part]
                val legStops = part.legStops
                fun leaves(stop: Int) = legStops[stop].time.plusSeconds(late.toLong())
                var board: MHDTableData? = null
                if (loaded.add(target)) {
                    locked[target] = data.busIDs[target.part]
                } else {
                    val lastStop = legStops.indices.lastOrNull { leaves(it) <= now }?.let { legStops[it].name } ?: "none"
                    val next = row(part, "1:99", lastStop, leaves(target.stop).plusSeconds(120), late)
                    val ours = row(part, "1:${target.part}", lastStop, leaves(target.stop), late)
                    val listsOurs = now < leaves(target.stop) && (unlistedFrom?.let { now < local(it) } ?: true)
                    board = part.liveConnection(if (listsOurs) listOf(ours, next) else listOf(next), null, legStops[target.stop].time, locked[target])
                    if (locked[target] == null && board?.type == "online") locked[target] = board.busID
                }
                data.receive(board, target, journey, now)
            }
            val update = journey.update(now, journey.delays(data.delays), data.passedStops, shownAlerts)
            update.alert?.let { alerts += it }
            shownAlerts = update.state.alerted
            steps += update
            now = now.plusSeconds(10)
        }
        return Replay(steps, alerts, data)
    }

    private fun assertCountsDown(replay: Replay, first: Int, alighting: Int, then: TripPhase, name: String) {
        val rides = replay.steps.filter { it.progress.phase == TripPhase.Ride(0) }
        val counts = rides.mapNotNull { it.progress.stopsLeft }
        assertTrue(
            "$name counts down every stop: $counts",
            counts.first() == first && counts.last() == 1 && counts.toSet() == (1..first).toSet() && counts.zipWithNext().all { (a, b) -> a >= b },
        )
        assertEquals("$name ends its ride at the alighting stop", alighting, rides.last().progress.nextStop)
        assertEquals(then, replay.steps.last().progress.phase)
        val offAlerts = replay.alerts.filter { it.key == "off-0" }
        assertEquals("$name alerts to get off once: ${replay.alerts.map { it.key }}", 1, offAlerts.size)
        assertEquals("Get off at the next stop", shown(offAlerts[0].title))
    }

    // The traveller's location

    /** [gps] moved [east] and [north] metres. */
    private fun moved(gps: StopGps, east: Double = 0.0, north: Double = 0.0) =
        StopGps(lon = gps.lon + east / (111_195 * cos(gps.lat * PI / 180)), lat = gps.lat + north / 111_195)

    /** [fraction] of the way from [leg]'s stop [stop] to the next one (past it above 1), [left] metres left of that line. */
    private fun onLine(stop: Int, fraction: Double = 0.0, left: Double = 0.0, leg: List<PartStop> = leg72): StopGps {
        val a = gps(leg[stop])
        val (x, y) = gps(leg[stop + 1]).meters(a)
        val length = hypot(x, y)
        return moved(a, east = fraction * x - left * y / length, north = fraction * y + left * x / length)
    }

    private fun fix(gps: StopGps, accuracy: Double = 10.0) = TripFix(gps, accuracy)

    /** One 15 s refresh of the service: the location against where the boards and the timetable have the vehicle. */
    private fun tick(journey: Journey, live: TripLiveData, fix: TripFix, now: Instant, alerted: Set<String> = emptySet(), found: List<Journey>? = null): TripUpdate {
        val missed = live.locate(fix, journey, found, now)
        return journey.update(now, journey.delays(live.delays), live.positions, alerted, fix, missed)
    }

    private class Followed(val steps: List<TripUpdate>, val alerts: List<String>, val live: TripLiveData, val missedAt: Instant?)

    /** Replays the traveller at [place] every 15 s; missedAt is when the location first found them off the ride. */
    private fun follow(
        journey: Journey = walkChange, from: String, to: String, live: TripLiveData = TripLiveData(), place: (Instant) -> StopGps,
    ): Followed {
        val data = live.copy()
        var alerted = emptySet<String>()
        val steps = mutableListOf<TripUpdate>()
        val alerts = mutableListOf<String>()
        var missedAt: Instant? = null
        var now = local(from)
        while (now <= local(to)) {
            val update = tick(journey, data, fix(place(now)), now, alerted)
            update.alert?.let { alerts += it.key }
            alerted = update.state.alerted
            if (missedAt == null && data.missed != null) missedAt = now
            steps += update
            now = now.plusSeconds(15)
        }
        return Followed(steps, alerts, data, missedAt)
    }

    @Test
    fun beforeBoardingWithAndWithoutALiveDelay() {
        val waiting = at("07:56")
        assertEquals(TripPhase.Board(0), waiting.progress.phase)
        assertEquals(listOf(TripStep.BOARD, "72"), listOf(waiting.state.step, waiting.state.line))
        assertEquals(
            listOf("Board at Hronská", "Platform A · in 4 min", "4 min"),
            listOf(waiting.state.titleText, waiting.state.detailText, waiting.state.compactText),
        )
        assertNull(waiting.state.delay)
        val late = at("07:56", live = mapOf(0 to 120))
        assertEquals("A 2-minute late 72 leaves at 08:02", listOf("Platform A · in 6 min", 2, local("08:02")), listOf(late.state.detailText, late.state.delay, late.state.time))
        assertEquals("Still waiting for the late 72 at 08:01", TripPhase.Board(0), at("08:01", live = mapOf(0 to 120)).progress.phase)
        // The time moves by the whole minutes the delay reads as, like the trip detail's times and change buffers.
        listOf(Triple(20, 0, "08:00"), Triple(50, 1, "08:01"), Triple(90, 2, "08:02"), Triple(-50, -1, "07:59")).forEach { (seconds, minutes, time) ->
            val shown = at("07:56", live = mapOf(0 to seconds)).state
            assertEquals("$seconds s late reads $time", minutes to local(time), shown.delay to shown.time)
        }
        assertEquals("Under a minute", "Platform A · in <1 min", at("07:59:30").state.detailText)
        assertEquals("Over an hour", "Platform A · at 08:00", at("06:50").state.detailText)
        assertEquals("The 31 leaves as the walk ends: a tight change ahead", TripWarning.TIGHT_CHANGE, waiting.state.warning)
        assertNull("No alert before boarding", waiting.alert)
        assertEquals("Watch only the boarding stop", listOf(TripTrackerTarget(0, 0)), walkChange.trackerTargets(waiting.progress))
    }

    @Test
    fun stopsLeftLivePositionDelayShiftAndTheFollowedStop() {
        val riding = at("08:10:30")
        assertEquals("Pažítková is next, 8 stops to Rajská", TripProgress(TripPhase.Ride(0), 10, 8, 0.56), riding.progress)
        assertEquals(
            listOf("Get off in 8 stops", "Rajská, platform A", "8 stops"),
            listOf(riding.state.titleText, riding.state.detailText, riding.state.compactText),
        )
        assertEquals(local("08:21"), riding.state.time)
        val behind = at("08:10:30", passedStops = mapOf(0 to 7))
        assertEquals("The vehicle left Brodná last: 10 stops left", listOf(10, 8), listOf(behind.progress.stopsLeft, behind.progress.nextStop))
        assertTrue(requireNotNull(behind.progress.fraction) < 0.56)
        assertEquals("A delay shifts every stop time", 8, at("08:12", live = mapOf(0 to 120)).progress.stopsLeft)
        assertEquals(
            "Follow the 72 at Pažítková and watch the 31 at Kollárovo nám.",
            listOf(TripTrackerTarget(0, 10), TripTrackerTarget(2, 0)), walkChange.trackerTargets(riding.progress),
        )
        val lastStop = at("08:20:30")
        assertEquals(1, lastStop.progress.stopsLeft)
        assertEquals("Get off at the next stop", lastStop.state.titleText)
        assertEquals(
            "The 72 ends at Rajská, so keep following it at Ondrejský cintorín",
            listOf(TripTrackerTarget(0, 16), TripTrackerTarget(2, 0)), walkChange.trackerTargets(lastStop.progress),
        )
        assertEquals("Left the alighting stop", "Get off now", at("08:20:30", passedStops = mapOf(0 to 17)).state.titleText)
    }

    @Test
    fun changeStepsPlatformsTightAndLikelyMissedChanges() {
        val change = at("08:25")
        assertEquals(TripPhase.Change(2), change.progress.phase)
        assertEquals(
            listOf("Walk to platform D", "Kollárovo nám. · 31 leaves in 6 min", "D · 6m", "31"),
            listOf(change.state.titleText, change.state.detailText, change.state.compactText, change.state.line),
        )
        assertEquals(TripWarning.TIGHT_CHANGE, change.state.warning)
        assertEquals(Triple("change-2", "Change to platform D", "Kollárovo nám. · 31 leaves in 6 min"), alertOf(change))
        assertEquals("Watch the 31", listOf(TripTrackerTarget(2, 0)), walkChange.trackerTargets(change.progress))
        val missed = at("08:15", live = mapOf(0 to 63))
        assertEquals(TripWarning.LIKELY_MISSED_CHANGE, missed.state.warning)
        assertEquals("A 63 s late 72 misses the 31", Triple("missed-2", "Change likely missed", "31 leaves Kollárovo nám. at 08:31"), alertOf(missed))
        val onTime = at("08:15", live = mapOf(0 to 29))
        assertEquals("Shown on time, the change is still tight", listOf(0, TripWarning.TIGHT_CHANGE, null), listOf(onTime.state.delay, onTime.state.warning, onTime.alert))
        assertEquals("Shown +1 min, it is likely missed", TripWarning.LIKELY_MISSED_CHANGE, at("08:15", live = mapOf(0 to 30)).state.warning)
        assertEquals("A late 31 still waits", TripWarning.TIGHT_CHANGE, at("08:15", live = mapOf(0 to 63, 2 to 120)).state.warning)
        val atStation = at("08:19", sameStopChanges)
        assertEquals(TripPhase.Change(1), atStation.progress.phase)
        assertEquals("Same-platform change", listOf("Wait at platform A", "42 leaves in 2 min"), listOf(atStation.state.titleText, atStation.state.detailText))
        assertEquals("Second same-platform change", "Wait at platform B", at("08:28", sameStopChanges).state.titleText)
        assertEquals(
            "The 72 continues past Autobusová stanica, so follow it there",
            listOf(TripTrackerTarget(0, 15), TripTrackerTarget(1, 0)), sameStopChanges.trackerTargets(at("08:17:30", sameStopChanges).progress),
        )
    }

    @Test
    fun lastLegWithAndWithoutStopsFinalWalkAndArrival() {
        val fallback = at("08:32")
        assertEquals("Without its stops the 31 counts minutes", TripProgress(TripPhase.Ride(2), 1, null, 0.5), fallback.progress)
        assertEquals(listOf("Get off in 1 min", "Zochova, platform B", "1 min"), listOf(fallback.state.titleText, fallback.state.detailText, fallback.state.compactText))
        assertEquals("Get off without stops", Triple("off-2", "Get off in 1 min", "Zochova, platform B"), alertOf(fallback))
        assertEquals("Follow the 31 at Zochova", listOf(TripTrackerTarget(2, 1)), walkChange.trackerTargets(fallback.progress))
        val lastLeg = at("08:32", sameStopChanges)
        assertEquals("Two-stop leg", 1 to "Get off at the next stop", lastLeg.progress.stopsLeft to lastLeg.state.titleText)
        val arrived = at("08:34")
        assertEquals(TripPhase.Arrived, arrived.progress.phase)
        assertEquals(listOf("Arrived", "Zochova", "Arrived"), listOf(arrived.state.titleText, arrived.state.detailText, arrived.state.compactText))
        assertEquals(local("08:33"), arrived.state.time)
        assertNull(arrived.state.warning)
        assertNull(arrived.alert)
        assertTrue("No sockets once arrived", walkChange.trackerTargets(arrived.progress).isEmpty())
        assertEquals("A late 31 is still riding", TripPhase.Ride(2), at("08:34", live = mapOf(2 to 120)).progress.phase)
        val walking = at("08:25", finalWalk)
        assertEquals(TripPhase.Walk(1), walking.progress.phase)
        assertEquals(listOf("Walk to Kollárovo nám.", "Arrive in 6 min", null), listOf(walking.state.titleText, walking.state.detailText, walking.state.line))
        assertEquals(TripPhase.Arrived, at("08:32", finalWalk).progress.phase)
        assertEquals("The walk ends later after a late vehicle", TripPhase.Walk(1), at("08:32", finalWalk, live = mapOf(0 to 120)).progress.phase)
    }

    @Test
    fun theDetailMarksTheStepTheTripIsAt() {
        assertEquals("Before boarding: the 72's step", JourneyStep.Ride(0), at("07:56").progress.step(walkChange))
        assertEquals(JourneyStep.Ride(0), at("08:10:30").progress.step(walkChange))
        assertEquals("Changing: the walk to the 31", JourneyStep.Walk(listOf(1), 2), at("08:25").progress.step(walkChange))
        assertEquals("A change at the same stop", JourneyStep.Change(1), at("08:19", sameStopChanges).progress.step(sameStopChanges))
        assertEquals("The final walk", JourneyStep.Walk(listOf(1), null), at("08:25", finalWalk).progress.step(finalWalk))
        assertNull("Arrived: no step", at("08:34").progress.step(walkChange))
        val walkFirst = Journey("walk-first", walkChange.parts.subList(1, 3), null)
        assertEquals("Before boarding after a walk: the walk to the stop", JourneyStep.Walk(listOf(0), 1), TripProgress(TripPhase.Board(1)).step(walkFirst))
    }

    @Test
    fun aStuckFlappingVehicleAlertsOncePerStep() {
        // A 72 stuck between Autobusová stanica and Ondrejský cintorín, its position flapping and its delay growing every
        // 10 s, then moving on to Rajská and the change.
        var alerted = emptySet<String>()
        val alerts = mutableListOf<String>()
        var dueWithoutMemory = 0
        var delay = 0
        var previousPhase: TripPhase = TripPhase.Board(0)
        val phases = mutableListOf<TripPhase>()
        for (second in 0..15 * 60 step 10) {
            val now = local("08:19").plusSeconds(second.toLong())
            if (second < 8 * 60) delay += 10 else if (second == 11 * 60) delay += 90 // stuck again right after arriving
            // Autobusová stanica, Ondrejský cintorín, Rajská.
            val position = if (second < 8 * 60) (if (second % 20 == 0) 15 else 16) else 17
            val update = walkChange.update(now, walkChange.delays(mapOf(0 to delay)), mapOf(0 to position), alerted)
            update.alert?.let { alerts += it.key }
            alerted = update.state.alerted
            if (walkChange.update(now, walkChange.delays(mapOf(0 to delay)), mapOf(0 to position)).alert != null) dueWithoutMemory++
            if (update.progress.phase != previousPhase) phases += update.progress.phase
            previousPhase = update.progress.phase
        }
        assertTrue("Phases flap: $phases", TripPhase.Change(2) in phases && phases.count { it == TripPhase.Ride(0) } == 2)
        assertTrue("The replay keeps alerts due on most ticks ($dueWithoutMemory)", dueWithoutMemory >= 40)
        // The delay shows as +1 min from 30 s (08:19:20), after the 72 left Ondrejský cintorín (08:19:10) for Rajská.
        assertEquals("Each alert once", listOf("off-0", "missed-2", "change-2", "off-2"), alerts)
    }

    @Test
    fun alertsAndLiveDataSurviveARestore() {
        val saved = SavedTrip(walkChange, TripLiveData(), at("08:25").state.alerted)
        val restored = savedTripGson.fromJson(savedTripGson.toJson(saved), SavedTrip::class.java)
        assertEquals(setOf("change-2"), restored.alerted)
        assertNull("No repeat after a restore", at("08:25:30", alerted = restored.alerted).alert)
        assertEquals("The journey too", walkChange, restored.journey)
        val locatedLive = TripLiveData(
            passedStops = mapOf(0 to 7), located = mapOf(0 to 5, 2 to 1), boarded = setOf(0), offRide = mapOf(2 to local("08:31:30")),
            missed = 2, kept = setOf(0),
        )
        val reloaded = savedTripGson.fromJson(savedTripGson.toJson(locatedLive), TripLiveData::class.java)
        assertEquals("Location data survives a restore", locatedLive, reloaded)
        assertEquals(mapOf(0 to 7, 2 to 1), reloaded.positions)
    }

    @Test
    fun delaySources() {
        val online = row(walkChange.parts[0], lastStop = "none", leaves = local("08:02"), late = 120)
        assertEquals("Only online rows are live", 120 to null, online.liveDelaySeconds to online.copy(type = "cp").liveDelaySeconds)
        val searchDelay = rJourneys[1]
        assertEquals(mapOf(0 to 63), searchDelay.delays(emptyMap()))
        assertEquals("Live delays win over the search-time delay", mapOf(0 to 120), searchDelay.delays(mapOf(0 to 120)))
        assertTrue("No delay data", walkChange.delays(emptyMap()).isEmpty())
    }

    @Test
    fun boardUpdatesSetTheFollowedVehiclesDelayAndTheStopItLeft() {
        val leg = walkChange.parts[0]
        val prievozska = TripTrackerTarget(0, 11)
        val boards = TripLiveData()
        boards.receive(row(leg, lastStop = "Bratislava, Pažítková", leaves = local("08:13")), prievozska, walkChange, local("08:12"))
        assertEquals("Prievozská's board lists the 72 with Pažítková, regional prefix and all", mapOf(0 to 10), boards.passedStops)
        assertEquals(mapOf(0 to "1:2552") to mapOf(0 to 0), boards.busIDs to boards.delays)
        assertEquals("7 stops from Prievozská", 7, at("08:12", passedStops = boards.passedStops).progress.stopsLeft)
        val seen = boards.copy()
        boards.receive(row(leg, lastStop = "Somewhere else", leaves = local("08:13")), prievozska, walkChange, local("08:12:10"))
        assertEquals("Unknown stops are ignored", seen.passedStops, boards.passedStops)
        boards.receive(null, prievozska, walkChange, local("08:12:30"))
        assertEquals("A reconnecting board before the vehicle is due is no departure", seen.passedStops, boards.passedStops)
        boards.receive(null, prievozska, walkChange, local("08:13:10"))
        assertEquals("Dropped from the board once due: it passed Prievozská", mapOf(0 to 11), boards.passedStops)
        assertEquals(mapOf(0 to "1:2552") to mapOf(0 to 0), boards.busIDs to boards.delays)
        val miletice = TripTrackerTarget(0, 12)
        boards.receive(null, miletice, walkChange, local("08:16:59"))
        assertEquals("A board that never listed the vehicle waits 3 minutes past its time", mapOf(0 to 11), boards.passedStops)
        boards.receive(null, miletice, walkChange, local("08:17:30"))
        assertNull(boards.passedStops[0])
        assertEquals("Then it counts by the timetable, which had it leave Košická at 08:17", 3, at("08:17:30", passedStops = boards.passedStops).progress.stopsLeft)
        boards.receive(null, TripTrackerTarget(2, 0), walkChange, local("08:40"))
        assertNull("A boarding stop's board tells no position", boards.passedStops[2])
    }

    @Test
    fun aRegionalRowStandingInForTheTimetableIsNeverTheFollowedVehicle() {
        val leg = walkChange.parts[0]
        val hronska = TripTrackerTarget(0, 0)
        val scheduled = leg.startDeparture
        val boards = TripLiveData()
        fun receive(row: MHDTableData) =
            boards.receive(leg.liveConnection(listOf(row), null, scheduled, boards.busIDs[0]), hronska, walkChange, scheduled)
        receive(row(leg, REGIONAL_BUS_ID, "none", scheduled.plusSeconds(60), late = 60))
        assertEquals("Its delay counts, but it names no vehicle", emptyMap<Int, String>() to mapOf(0 to 60), boards.busIDs to boards.delays)
        receive(row(leg, lastStop = "none", leaves = scheduled.plusSeconds(120), late = 120))
        assertEquals("The socket row gone live is the vehicle then", mapOf(0 to "1:2552") to mapOf(0 to 120), boards.busIDs to boards.delays)
    }

    @Test
    fun boardsThatListAVehicleUntilItLeavesCountDownEveryStop() {
        // The 72 ends at Rajská, so its board never lists it: follow it to Ondrejský cintorín.
        assertCountsDown(replayBoards(walkChange, 60, "07:58", "08:24"), 17, 17, TripPhase.Change(2), "The 72 to Rajská")
        // The 72 goes on past Autobusová stanica, whose board lists it.
        assertCountsDown(replayBoards(sameStopChanges, 60, "07:58", "08:20"), 15, 15, TripPhase.Change(1), "The 72 to Autobusová stanica")
    }

    @Test
    fun aVehicleTheBoardsStopListingCountsByTheTimetable() {
        // From 08:05 no board lists the 72: once its next stop is 3 minutes overdue, it counts by the timetable, so the
        // alert to get off comes when the timetable alone would give it.
        listOf(
            listOf(walkChange, 17, "08:20", "08:24", TripPhase.Change(2)),
            listOf(sameStopChanges, 15, "08:17", "08:20", TripPhase.Change(1)),
        ).forEach { (journey, alighting, alertsAt, end, then) ->
            val unlisted = replayBoards(journey as Journey, 0, "07:58", end as String, unlistedFrom = "08:05")
            val rides = unlisted.steps.filter { it.progress.phase == TripPhase.Ride(0) }
            val counts = rides.mapNotNull { it.progress.stopsLeft }
            val alertTimes = unlisted.steps.indices.filter { unlisted.steps[it].alert?.key == "off-0" }.map { local("07:58").plusSeconds(it * 10L) }
            assertTrue("The unlisted 72 counts down: $counts", counts.last() == 1 && counts.zipWithNext().all { (a, b) -> a >= b })
            assertEquals(alighting, rides.last().progress.nextStop)
            assertEquals(then, unlisted.steps.last().progress.phase)
            assertEquals("The unlisted 72 alerts to get off at $alertsAt", listOf(local(alertsAt as String)), alertTimes)
            assertEquals("Get off at the next stop", shown(unlisted.alerts.first { it.key == "off-0" }.title))
        }
    }

    @Test
    fun liveDataSurvivesARestoreAndTheTripDoesNotEndEarlyOnStaleDelays() {
        val beforeRestore = replayBoards(walkChange, 60, "07:58", "08:05")
        val saved = savedTripGson.fromJson(savedTripGson.toJson(beforeRestore.live), TripLiveData::class.java)
        assertEquals("Saved live data", beforeRestore.live, saved)
        assertEquals(listOf<Any?>(mapOf(0 to 4), "1:0", 60), listOf(saved.passedStops, saved.busIDs[0], saved.delays[0]))
        assertTrue("Boards that listed it are saved too", saved.listed.isNotEmpty())
        val restored = replayBoards(walkChange, 60, "08:12", "08:24", live = saved, alerted = beforeRestore.steps.last().state.alerted)
        assertEquals("Catches up at once", 7, restored.steps[0].progress.stopsLeft)
        assertCountsDown(restored, 7, 17, TripPhase.Change(2), "The restored 72")
        val restoredDelays = savedTripGson.fromJson(savedTripGson.toJson(TripLiveData(delays = mapOf(2 to 120))), TripLiveData::class.java).delays
        assertEquals(TripPhase.Arrived, at("08:34").progress.phase)
        assertEquals("The saved live delay keeps a late 31 riding", TripPhase.Ride(2), at("08:34", live = restoredDelays).progress.phase)
        assertFalse("Arrival on saved delays ends only with live data", tripCanEnd(local("08:34"), local("08:33"), awaitsLiveData = true))
        assertTrue("or 10 minutes later", tripCanEnd(local("08:43"), local("08:33"), awaitsLiveData = true))
        assertTrue(tripCanEnd(local("08:34"), local("08:33"), awaitsLiveData = false))
        // Saved on time, the 72 to Autobusová stanica runs 10 minutes late. At 08:25 the saved delay has it arrived, so
        // the service asks the alighting stop's board, which first answers empty, then lists the 72 having left Novohradská.
        val lateRide = sameStopChanges.copy(parts = listOf(sameStopChanges.parts[0]))
        val lateLeg = lateRide.parts[0]
        val stale = TripLiveData(delays = mapOf(0 to 0))
        val alighting = lateRide.trackerTargets(TripProgress(TripPhase.Ride(0)))
        assertEquals(TripPhase.Arrived, lateRide.progress(local("08:25"), lateRide.delays(stale.delays)).phase)
        assertEquals("The restored trip asks Autobusová stanica", listOf(TripTrackerTarget(0, 15)), alighting)
        stale.receive(null, alighting[0], lateRide, local("08:25"))
        assertTrue("An empty board tells no position", stale.passedStops.isEmpty())
        stale.receive(
            row(lateLeg, lastStop = lateLeg.legStops[13].name, leaves = lateLeg.legStops[15].time.plusSeconds(600), late = 600),
            alighting[0], lateRide, local("08:25:05"),
        )
        val resumed = lateRide.update(local("08:25:05"), lateRide.delays(stale.delays), stale.passedStops)
        assertEquals("The late 72 resumes 2 stops before Autobusová stanica", "Get off in 2 stops", resumed.state.titleText)
        assertNull(resumed.alert)
    }

    @Test
    fun walkingGuidanceToThePlatformTheChangeAndTheDestination() {
        val rad = PI / 180
        val haversine = 2 * 6_371_008.8 * asin(
            sqrt(sin((rajska.lat - hronska.lat) * rad / 2).pow(2) + cos(hronska.lat * rad) * cos(rajska.lat * rad) * sin((rajska.lon - hronska.lon) * rad / 2).pow(2))
        )
        assertTrue("Planar metres match the great circle across the city", kotlin.math.abs(hronska.distance(rajska) - haversine) < haversine / 500)
        val waiting = at("07:56")
        assertEquals("At the platform: the board step as without a location", waiting.state, at("07:56", fix = fix(hronska)).state)
        assertEquals("Within 50 m counts as at the platform", "Board at Hronská", at("07:56", fix = fix(moved(hronska, north = 45.0))).state.titleText)
        assertEquals("Or within the location's accuracy", "Board at Hronská", at("07:56", fix = fix(moved(hronska, north = 90.0), accuracy = 100.0)).state.titleText)
        val toHronska = at("07:50", fix = fix(moved(hronska, east = 354.0)))
        assertEquals(listOf("Walk to Hronská", "350 m to platform A · leaves in 10 min"), listOf(toHronska.state.titleText, toHronska.state.detailText))
        assertEquals(TripWarning.TIGHT_CHANGE, toHronska.state.warning)
        assertNull(toHronska.alert)
        listOf(80.0 to "80 m", 346.0 to "350 m", 944.0 to "940 m", 949.0 to "950 m", 951.0 to "1.0 km", 1_234.0 to "1.2 km").forEach { (meters, text) ->
            assertEquals("$meters m", "$text to platform A · leaves at 08:00", at("06:50", fix = fix(moved(hronska, north = meters))).state.detailText)
        }
        val toPlatform = at("08:25", fix = fix(moved(kollarovo, north = 200.0)))
        assertEquals(listOf("Walk to platform D", "200 m to Kollárovo nám. · 31 leaves in 6 min"), listOf(toPlatform.state.titleText, toPlatform.state.detailText))
        assertEquals(TripWarning.TIGHT_CHANGE, toPlatform.state.warning)
        val atPlatform = at("08:25", fix = fix(kollarovo))
        assertEquals(listOf("Wait at platform D", "Kollárovo nám. · 31 leaves in 6 min"), listOf(atPlatform.state.titleText, atPlatform.state.detailText))
        val station = requireNotNull(sameStopChanges.parts[1].startStopGps)
        val backToStation = at("08:19", sameStopChanges, fix = fix(moved(station, east = -100.0)))
        assertEquals("A same-stop change walks when the location is away", listOf("Walk to platform A", "100 m · 42 leaves in 2 min"), listOf(backToStation.state.titleText, backToStation.state.detailText))
        assertEquals(at("08:19", sameStopChanges).state, at("08:19", sameStopChanges, fix = fix(station)).state)
        val destination = requireNotNull(finalWalk.parts[1].endStopGps)
        val farFromDestination = at("08:25", finalWalk, fix = fix(moved(destination, north = -1_234.0)))
        assertEquals(listOf("Walk to Kollárovo nám.", "1.2 km · arrive in 6 min"), listOf(farFromDestination.state.titleText, farFromDestination.state.detailText))
        assertEquals(at("08:25", finalWalk).state, at("08:25", finalWalk, fix = fix(destination)).state)
        val unplacedChange = walkChange.withParts { index, part -> if (index == 2) part.copy(startStopGps = null) else part }
        assertEquals("A change stop without a position is not where the traveller waits", "Walk to platform D", at("08:25", unplacedChange, fix = fix(rajska)).state.titleText)
    }

    @Test
    fun aWalkLongerThanTheTimeLeftWarnsOnceThatTheVehicleMayLeaveFirst() {
        val hurry = at("07:56", fix = fix(moved(hronska, east = 600.0)))
        assertEquals(TripWarning.MAY_MISS, hurry.state.warning)
        assertEquals("600 m in 4 min", Triple("hurry-0", "You may miss 72", "600 m to platform A · leaves in 4 min"), alertOf(hurry))
        val stillFar = at("07:56:15", alerted = hurry.state.alerted, fix = fix(moved(hronska, east = 590.0)))
        assertEquals("The hurry alert fires once", TripWarning.MAY_MISS to null, stillFar.state.warning to stillFar.alert)
        val near = at("07:56", fix = fix(moved(hronska, east = 100.0)))
        assertEquals("100 m in 4 min leaves time", TripWarning.TIGHT_CHANGE to null, near.state.warning to near.alert)
        val both = at("07:56", live = mapOf(0 to 63), fix = fix(moved(hronska, east = 600.0)))
        assertEquals("A likely missed change outranks the walk", TripWarning.LIKELY_MISSED_CHANGE to "missed-2", both.state.warning to both.alert?.key)
        val changeHurry = at("08:25", fix = fix(rajska))
        assertEquals(listOf("Walk to platform D", "620 m to Kollárovo nám. · 31 leaves in 6 min"), listOf(changeHurry.state.titleText, changeHurry.state.detailText))
        assertEquals(TripWarning.MAY_MISS, changeHurry.state.warning)
        assertEquals("Rajská to Kollárovo nám. in 6 min", "hurry-2" to "You may miss 31", alertOf(changeHurry)?.let { it.first to it.second })
        assertEquals("Then the change alert", "change-2", at("08:25:15", alerted = changeHurry.state.alerted, fix = fix(rajska)).alert?.key)
        // The change starts at the 72's whole-minute arrival, when it may still be a stop before Rajská: the walk counts
        // from the location only a minute later, not from the moving vehicle.
        val onBoard = fix(onLine(16, 1 - 300 / gps(leg72[16]).distance(rajska)))
        val arriving = at("08:21:05", fix = onBoard)
        assertEquals(TripPhase.Change(2), arriving.progress.phase)
        assertEquals("On the 72 300 m before Rajská as the change starts: as without a location", at("08:21:05").state, arriving.state)
        assertNotEquals(TripWarning.MAY_MISS, arriving.state.warning)
        assertEquals("change-2", arriving.alert?.key)
        assertEquals("Still there a minute later, the location counts", TripWarning.MAY_MISS, at("08:22", fix = onBoard).state.warning)
        val lateConnection = at("08:25", live = mapOf(2 to 300), fix = fix(rajska))
        assertNull("A 31 5 minutes late leaves time", lateConnection.state.warning)
        assertTrue(lateConnection.state.detailText.endsWith("31 leaves in 11 min"))
        assertEquals("change-2", lateConnection.alert?.key)
    }

    @Test
    fun theLocationCountsTheStopsReachedWithoutMovingTheVehicleItIsCheckedAgainst() {
        val rideLive = TripLiveData()
        val ahead = tick(walkChange, rideLive, fix(onLine(5, 0.5)), local("08:04:30"))
        assertEquals("The timetable has Ríbezľová next", 5, at("08:04:30").progress.nextStop)
        assertTrue(rideLive.passedStops.isEmpty() && rideLive.offRide.isEmpty())
        assertEquals("Between Ríbezľová and Ondrejovova", mapOf(0 to 5) to setOf(0), rideLive.located to rideLive.boarded)
        assertEquals("The location has Ondrejovova next", listOf<Any?>(6, 12, "Get off in 12 stops"), listOf(ahead.progress.nextStop, ahead.progress.stopsLeft, ahead.state.titleText))
        val stray = tick(walkChange, rideLive, fix(onLine(10)), local("08:04:45"))
        assertEquals("At Pažítková, Pažítková counts as reached", listOf<Any?>(mapOf(0 to 10), 11, 7), listOf(rideLive.located, stray.progress.nextStop, stray.progress.stopsLeft))
        tick(walkChange, rideLive, fix(onLine(5, 0.5)), local("08:05"))
        assertTrue("Back near Ríbezľová still rides the vehicle the timetable has there", rideLive.offRide.isEmpty() && rideLive.passedStops.isEmpty())
        assertEquals("And the count does not go back", mapOf(0 to 10), rideLive.located)
        val endLive = TripLiveData()
        val getOff = tick(walkChange, endLive, fix(rajska), local("08:20"))
        assertEquals("At Rajská before the timetable gets there", listOf<Any?>(mapOf(0 to 17), 0, "Get off now", "off-0"), listOf(endLive.located, getOff.progress.stopsLeft, getOff.state.titleText, getOff.alert?.key))
        assertEquals(1, at("08:20").progress.stopsLeft)
    }

    @Test
    fun aMissedBoardingShows45SecondsAfterTheVehicleIsAStopAhead() {
        fun vehicleStop(time: String, live: Map<Int, Int>) = requireNotNull(walkChange.progress(local(time), walkChange.delays(live)).fraction) * 17
        val minuteLate = TripLiveData(delays = mapOf(0 to 60))
        assertTrue("The 72 a minute late is a stop past Hronská from 08:02", vehicleStop("08:01:45", minuteLate.delays) < 1 && vehicleStop("08:02", minuteLate.delays) > 1)
        // Checked against where the 72 was a minute before, as whole-minute times may run that far ahead. A minute late, it
        // also makes the change to the 31 likely missed; the missed alert waits for the search, which this replay does not run.
        val stayed = follow(from = "08:00", to = "08:04", live = minuteLate) { hronska }
        assertEquals(local("08:03:45"), stayed.missedAt)
        assertTrue(stayed.live.boarded.isEmpty())
        assertEquals(listOf("missed-2"), stayed.alerts)
        assertEquals("Missed 72", stayed.steps.last().state.titleText)
        val afterMissed = stayed.live.copy()
        afterMissed.receive(fix(onLine(10)), walkChange, TripProgress(TripPhase.Ride(0), fraction = 0.5), local("08:03"))
        assertEquals("Once missed, the location is no longer checked", stayed.live, afterMissed)
        val unreported = follow(from = "08:00", to = "08:04:45") { hronska }
        assertTrue("Without a live delay the 72 may only be late", unreported.missedAt == null && unreported.live.offRide.isEmpty())
        assertEquals("Missed 45 s after 5 minutes past its departure", local("08:05:45"), follow(from = "08:00", to = "08:06") { hronska }.missedAt)
        // Riding a 72 running late with no live delay: behind the timetable's vehicle, but moving along its line.
        listOf(90L, 120L, 180L).forEach { lateness ->
            val late = follow(from = "08:03:30", to = "08:15") { now ->
                val stop = requireNotNull(walkChange.progress(now.minusSeconds(lateness), emptyMap()).fraction) * 17
                onLine(stop.toInt(), stop - stop.toInt())
            }
            assertTrue("On a 72 $lateness s late without live data", late.missedAt == null && late.live.boarded == setOf(0))
        }
        // Riding up to a minute behind the whole-minute timetable, the 72 on time on the boards: still on board.
        val liveOnTime = TripLiveData(delays = mapOf(0 to 0))
        listOf(20L, 45L, 59L).forEach { lag ->
            val behind = follow(from = "08:01", to = "08:15", live = liveOnTime) { now ->
                val stop = requireNotNull(walkChange.progress(now.minusSeconds(lag), mapOf(0 to 0)).fraction) * 17
                onLine(stop.toInt(), stop - stop.toInt())
            }
            assertNull("On the 72 $lag s behind its timetable", behind.missedAt)
        }
        // A leg whose stops did not load has only its boarding and alighting stops: 500 m behind counts as missed too.
        val stopless = walkChange.withParts { index, part -> if (index == 0) part.copy(stops = null) else part }
        val leftAtStop = follow(stopless, from = "08:00", to = "08:06", live = liveOnTime) { hronska }
        assertTrue("Missed on a stopless leg: ${leftAtStop.missedAt}", leftAtStop.missedAt?.let { it < local("08:05") } == true)
    }

    @Test
    fun offTheRouteAfterRidingButNotWhenOffTheLineNearTheAlightingStop() {
        val liveOnTime = TripLiveData(delays = mapOf(0 to 0))
        val offRoute = follow(from = "08:01:30", to = "08:03", live = liveOnTime) { now ->
            if (now < local("08:02")) onLine(1, 0.5) else onLine(2, left = 1_000.0)
        }
        assertEquals("Rode past Toryská, then 1 km off the line for 45 s", local("08:02:45"), offRoute.missedAt)
        assertEquals(setOf(0), offRoute.live.boarded)
        assertEquals("Off the route", offRoute.steps.last().state.titleText)
        assertTrue(offRoute.alerts.isEmpty())
        val lateBoard = TripLiveData(delays = mapOf(0 to 120))
        val lastLength = gps(leg72[16]).distance(rajska)
        val gotOff = follow(from = "08:20", to = "08:22:45", live = lateBoard) { onLine(16, 1 + 290 / lastLength) }
        assertNull("290 m past Rajská, off the line: got off", gotOff.missedAt)
        assertEquals(mapOf(0 to 17), gotOff.live.located)
        assertTrue(gotOff.steps.all { it.state.titleText == "Get off now" })
        // Once at Rajská the ride is done, though the delay still has it running: walking off is no miss.
        val walkedOn = follow(from = "08:20", to = "08:22:45", live = lateBoard) { now ->
            if (now < local("08:20:30")) rajska else moved(rajska, north = 1_000.0)
        }
        assertNull("Walked on from Rajská", walkedOn.missedAt)
        val wandered = follow(from = "08:20", to = "08:21", live = lateBoard) { onLine(16, 1 + 400 / lastLength) }
        assertEquals("400 m past Rajská is too far from it", local("08:20:45"), wandered.missedAt)
    }

    @Test
    fun theMissedStepOffersTheSoonestOtherConnectionAndAlertsOnce() {
        val lostAt = at("08:02", missed = TripMissed(0, boarded = false))
        assertEquals(
            "Missed while searching",
            listOf<Any?>(TripStep.MISSED, "72", "Missed 72", "Finding other connections…", local("08:00"), "Missed", null, null, null),
            with(lostAt.state) { listOf(step, line, titleText, detailText, time, compactText, delay, progress, warning) },
        )
        assertNull(lostAt.alert)
        val nothing = TripMissed(0, boarded = false, alternatives = TripAlternatives())
        val noneFound = at("08:02:15", alerted = lostAt.state.alerted, missed = nothing)
        assertEquals("No other connections found", noneFound.state.detailText)
        assertEquals("Nothing found", Triple("lost-0", "Missed 72", "No other connections found"), alertOf(noneFound))
        assertNull("No repeat", at("08:02:30", alerted = noneFound.state.alerted, missed = nothing).alert)

        /** This trip [minutes] later, on [line] instead of the 72, which leaves [delay] seconds late at the search. */
        fun later(minutes: Long, line: String = "72", delay: Int? = null): Journey {
            val shifted = walkChange.withParts { _, part ->
                part.copy(
                    startDeparture = part.startDeparture.plusSeconds(minutes * 60),
                    endArrival = part.endArrival.plusSeconds(minutes * 60),
                    stops = part.stops?.map { it.copy(time = it.time.plusSeconds(minutes * 60)) },
                )
            }
            return shifted.copy(
                id = "$line $minutes" + (delay?.let { " +$it s" } ?: ""),
                parts = shifted.parts.mapIndexed { index, part -> if (index == 0) part.copy(routeShortName = line, delaySeconds = delay) else part },
            )
        }
        val found = listOf(
            later(-5), later(10), later(20), later(30), later(15, "83"), later(5, "83"), later(25, "83"), later(20, "83"),
            later(-1, "83", 240), later(-1, "83", 0),
        )
        assertEquals("Ride lines", listOf("72", "31") to listOf("31"), walkChange.lines() to walkChange.lines(1))
        val alternatives = walkChange.alternatives(found, 0, local("08:02"))
        assertEquals("Departed ones dropped, two on the 72 and 31", listOf("72 10", "72 20"), alternatives.sameRoute.map { it.id })
        assertEquals("Three others by arrival", listOf("83 -1 +240 s", "83 5", "83 15"), alternatives.others.map { it.id })
        assertEquals("83 -1 +240 s", alternatives.best?.id)
        val missed = follow(from = "08:00", to = "08:04", live = TripLiveData(delays = mapOf(0 to 60))) { hronska }.live
        val offered = tick(walkChange, missed.copy(), fix(hronska), local("08:02:50"), alerted = setOf("lost-0"), found = found)
        assertEquals(
            "The soonest arrival, its search delay included",
            listOf<Any?>(TripStep.MISSED, "83", local("08:03"), "Take 83 at 08:03 from Hronská A", null),
            listOf(offered.state.step, offered.state.line, offered.state.time, offered.state.detailText, offered.alert),
        )
        val firstOffer = tick(walkChange, missed.copy(), fix(hronska), local("08:02:50"), found = found)
        assertEquals("The alert says what to take", Triple("lost-0", "Missed 72", "Take 83 at 08:03 from Hronská A"), alertOf(firstOffer))
    }

    @Test
    fun keptRidesAreNoLongerChecked() {
        val keptLive = TripLiveData(delays = mapOf(0 to 60), kept = setOf(0))
        val keptRide = follow(from = "08:00", to = "08:06", live = keptLive) { hronska }
        assertNull(keptRide.missedAt)
        assertEquals(keptLive, keptRide.live)
        assertEquals(TripStep.RIDE, keptRide.steps.last().state.step)
    }

    @Test
    fun aSavedTripIsRestoredUntilItWouldEnd() {
        assertFalse("Riding", SavedTrip(walkChange, TripLiveData(), emptySet()).isOver(local("08:10")))
        assertFalse("Arrived, but a restored trip waits 10 minutes for live data", SavedTrip(walkChange, TripLiveData(), emptySet()).isOver(local("08:40")))
        assertTrue(SavedTrip(walkChange, TripLiveData(), emptySet()).isOver(local("08:43")))
        assertFalse("A saved delay keeps the 31 riding", SavedTrip(walkChange, TripLiveData(delays = mapOf(2 to 600)), emptySet()).isOver(local("08:43")))
        assertTrue("A missed trip ends when it would have arrived", SavedTrip(walkChange, TripLiveData(missed = 0), emptySet()).isOver(local("08:34")))
    }

    @Test
    fun theDeviceStaysAwakeOnlyNearAStep() {
        fun near(time: String, journey: Journey = walkChange, missed: TripMissed? = null, live: Map<Int, Int> = emptyMap()): Boolean {
            val update = at(time, journey, live, missed = missed)
            return journey.isNearAlert(update.progress, update.state, journey.delays(live), local(time))
        }
        assertEquals("Boarding: from 3 minutes before the 72 leaves", listOf(false, true), listOf(near("07:56"), near("07:57")))
        assertEquals(
            "Riding: the first 7 minutes, then 3 minutes before the arrival",
            listOf(true, true, false, true), listOf(near("08:05"), near("08:07"), near("08:10"), near("08:18:30")),
        )
        assertEquals(
            "The 72 leaving 5 minutes late: the first 7 minutes from when it left, while a missed boarding may still show",
            listOf(true, false), listOf(near("08:12", live = mapOf(0 to 300)), near("08:12:30", live = mapOf(0 to 300))),
        )
        assertTrue("Changing, 3 minutes before the 31 leaves", near("08:28:30"))
        assertFalse("Walking", near("08:25", finalWalk))
        assertFalse("Arrived", near("08:34"))
        assertFalse("Missed", near("08:02", missed = TripMissed(0, boarded = false)))
    }

    @Test
    fun aJourneyWithoutPositionsTakesTheStationsOnes() {
        val unplaced = walkChange.withParts { _, part -> part.copy(startStopGps = null, endStopGps = null) }
        val placed = unplaced.positioned(stops)
        assertEquals("Hronská's position from the stops list", stops.stop(1386, "Hronská", "A")?.let { StopGps(lon = it.lng, lat = it.lat) }, placed.parts[0].startStopGps)
        assertTrue("Every part placed", placed.parts.all { it.startStopGps != null && it.endStopGps != null })
        assertEquals("Known positions stay", walkChange, walkChange.positioned(stops))
    }
}
