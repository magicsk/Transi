package eu.magicsk.transi

import eu.magicsk.transi.data.models.FollowedTrip
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.SavedTrip
import eu.magicsk.transi.data.models.TripAlert
import eu.magicsk.transi.data.models.TripFix
import eu.magicsk.transi.data.models.TripLiveData
import eu.magicsk.transi.data.models.TripMissed
import eu.magicsk.transi.data.models.TripPhase
import eu.magicsk.transi.data.models.TripProgress
import eu.magicsk.transi.data.models.TripState
import eu.magicsk.transi.data.models.TripTrackerTarget
import eu.magicsk.transi.data.models.delays
import eu.magicsk.transi.data.models.distance
import eu.magicsk.transi.data.models.isNearAlert
import eu.magicsk.transi.data.models.legStops
import eu.magicsk.transi.data.models.liveBoardStop
import eu.magicsk.transi.data.models.liveConnection
import eu.magicsk.transi.data.models.liveDelaySeconds
import eu.magicsk.transi.data.models.partStops
import eu.magicsk.transi.data.models.positioned
import eu.magicsk.transi.data.models.stop
import eu.magicsk.transi.data.models.trackerTargets
import eu.magicsk.transi.data.models.tripCanEnd
import eu.magicsk.transi.data.models.update
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.FollowedTrips
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.repository.LiveBoard
import eu.magicsk.transi.util.gps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Follows one journey step by step until it arrives (iOS TripLiveActivityController): its vehicles on the live boards of
 * the stops [trackerTargets] picks, the traveller's location from [fix], the other connections after a missed ride, and
 * the saved copy in [trips], which restores it after the process ends. [LiveUpdatesService] owns it, shows [onUpdate]'s
 * step and [onEnd]'s last one. [saved] is the trip restored; its delays may be stale until a board answers. Runs on the
 * main thread of [parentScope].
 */
class TripFollower(
    journey: Journey,
    saved: SavedTrip?,
    private val stops: Deferred<List<Stop>>,
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    private val trips: FollowedTrips,
    parentScope: CoroutineScope,
    private val search: suspend (from: Stop, to: Stop, at: Instant) -> List<Journey>,
    private val fix: () -> TripFix?,
    private val onUpdate: (TripState, TripAlert?) -> Unit,
    private val onEnd: (TripState) -> Unit,
) {
    private var journey = journey
    private var live = saved?.live ?: TripLiveData()
    /** Set for a restored trip until a live board answers, so it does not end on stale saved delays. */
    private var awaitsLiveData = saved != null
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))
    private val boards = mutableMapOf<TripTrackerTarget, LiveBoard>()
    private var stopList = emptyList<Stop>()
    /** The journeys found to the destination since the ride was missed; null until the search answers. */
    private var found: List<Journey>? = null
    private var searchedAt: Instant? = null
    private var progress: TripProgress? = null
    private var missed: TripMissed? = null
    private var ended = false

    /** The step shown; a restored trip keeps the alerts it already gave. */
    var state: TripState = Instant.now().let { now ->
        journey.update(now, journey.delays(live.delays), live.positions, missed = live.locate(null, journey, null, now)).state
    }.let { if (saved == null) it else it.copy(alerted = saved.alerted) }
        private set

    /** Whether the service keeps the device awake for the refreshes near a step. */
    fun isNearAlert(now: Instant) = progress?.let { journey.isNearAlert(it, state, now) } ?: false

    /** Positions the journey, loads the stops R-API legs leave out, then refreshes every 15 s and on every board update. */
    fun start() {
        trips.publish(FollowedTrip(journey, progress, state, live.delays, missed))
        scope.launch {
            stopList = stops.await()
            // The location is checked against the stops' positions.
            journey = journey.positioned(stopList)
            save()
            loadStops()
            while (isActive) {
                refresh()
                delay(15_000)
            }
        }
    }

    /** Ends the trip by the user: the saved copy goes too. */
    fun end() {
        close()
        trips.clear()
    }

    /** Stops following, keeping the saved copy for the app to restore. */
    fun close() {
        ended = true
        scope.cancel()
        boards.values.forEach(LiveBoard::close)
        boards.clear()
        trips.publish(null)
    }

    /** Keeps following the trip after the location found the traveller off it, which then no longer checks that ride. */
    fun keepFollowing() {
        val index = live.missed ?: return
        live.kept = live.kept + index
        live.missed = null
        live.offRide = emptyMap()
        found = null
        searchedAt = null
        save()
        refresh()
    }

    /** Recomputes the step, and passes it on when it changed. */
    fun refresh() {
        if (ended) return
        val now = Instant.now()
        val fix = fix()
        val before = live.copy()
        val missed = live.locate(fix, journey, found, now)
        if (live != before) save()
        if (missed != null && missed.alternatives?.isEmpty != false) findAlternatives(missed.part, now, fix)
        this.missed = missed
        val update = journey.update(now, journey.delays(live.delays), live.positions, state.alerted, fix, missed)
        progress = update.progress
        when {
            // Once a ride is missed, the trip's vehicles no longer matter.
            update.progress.phase != TripPhase.Arrived -> watch(if (missed == null) journey.trackerTargets(update.progress) else emptyList())
            // A missed trip ends when it would have arrived.
            missed != null || tripCanEnd(now, update.state.time, awaitsLiveData) -> {
                end()
                onEnd(update.state)
                return
            }
            // Ask the last vehicle's board whether it runs later than saved.
            else -> watch(
                journey.parts.indexOfLast { !it.isWalk }.takeIf { it >= 0 }
                    ?.let { journey.trackerTargets(TripProgress(TripPhase.Ride(it))) }.orEmpty()
            )
        }
        val changed = update.state != state
        state = update.state
        trips.publish(FollowedTrip(journey, progress, state, live.delays, missed))
        if (!changed) return
        if (update.alert != null) save()
        onUpdate(state, update.alert)
    }

    private fun save() = trips.save(SavedTrip(journey, live, state.alerted))

    /** Loads the stop lists that R-API legs leave out, with the B-API delay; a leg whose fetch fails keeps its two ends. */
    private fun loadStops() {
        val parts = journey.parts
        val missing = parts.indices.filter { parts[it].stops == null && parts[it].tripId != null }
        if (missing.isEmpty()) return
        scope.launch {
            val session = idsbk.token()
            val runs = missing.map { index ->
                async { session?.let { token -> parts[index].tripId?.let { repository.getTripStops(token, it).data } } }
            }.awaitAll()
            val loaded = withContext(Dispatchers.Default) {
                journey.copy(parts = journey.parts.mapIndexed { index, part ->
                    val position = missing.indexOf(index)
                    if (position < 0) {
                        part
                    } else {
                        val trip = runs[position]
                        part.copy(stops = trip?.partStops(part, stopList), delaySeconds = trip?.trip_delay ?: part.delaySeconds)
                    }
                })
            }
            // Stop indices now point into the full stop lists.
            watch(emptyList())
            live.passedStops = live.passedStops - missing.toSet()
            live.located = live.located - missing.toSet()
            live.listed = live.listed.filterKeys { it.part !in missing }
            journey = loaded
            save()
            refresh()
        }
    }

    /**
     * Searches from the stop nearest [fix] to the trip's destination, again 2 minutes after a search found nothing still
     * to catch. Without a location it searches from the boarding stop of a ride not boarded, and waits for one after a
     * boarded ride, which took the traveller away from that stop.
     */
    private fun findAlternatives(index: Int, now: Instant, fix: TripFix?) {
        if (fix == null && index in live.boarded) return
        if (searchedAt?.let { Duration.between(it, now).seconds < 120 } == true) return
        searchedAt = now
        val part = journey.parts[index]
        // Measured from the fix: the stops list is sorted by the location only some time after it updates.
        val from = if (fix != null) stopList.filter { it.id > 0 }.minByOrNull { fix.gps.distance(it.gps) }
        else stopList.stop(part.startStationId, part.startStopName, part.startStopCode)
        val last = journey.parts.last()
        val to = stopList.stop(last.endStationId, last.endStopName, null)
        if (from == null || to == null) {
            found = emptyList()
            return
        }
        scope.launch {
            val journeys = search(from, to, now)
            if (live.missed != index) return@launch
            found = journeys
            refresh()
        }
    }

    /** Keeps one live board per target and closes the rest, so only the current step's sockets stay open. */
    private fun watch(targets: List<TripTrackerTarget>) {
        boards.keys.filter { it !in targets }.forEach { boards.remove(it)?.close() }
        for (target in targets) {
            if (target in boards) continue
            val part = journey.parts[target.part]
            // Null while the stops list is still loading; the next refresh tries again.
            val boardStop = part.liveBoardStop(stopList, if (target.stop == 0) null else part.legStops[target.stop]) ?: continue
            boards[target] = LiveBoard(repository, idsbk, scope).apply {
                // Until the whole board loaded, a vehicle missing from it may only be missing from the regional rows.
                onDepartures = { rows ->
                    if (hasWholeBoard(boardStop.platform ?: -1)) {
                        receive(part.liveConnection(rows, boardStop.platform, boardStop.scheduled, live.busIDs[target.part]), target)
                    }
                }
                setStop(boardStop.stop)
                connect()
            }
        }
    }

    private fun receive(connection: MHDTableData?, target: TripTrackerTarget) {
        if (ended) return
        val followed = Triple(live.delays, live.busIDs, live.passedStops)
        live.receive(connection, target, journey, Instant.now())
        if (connection?.liveDelaySeconds != null) awaitsLiveData = false
        // Saved when a delay, the vehicle or its position changes, not on every board update.
        if (followed != Triple(live.delays, live.busIDs, live.passedStops)) save()
        refresh()
    }
}
