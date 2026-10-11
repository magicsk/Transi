package eu.magicsk.transi.view_models

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParseException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.MHDTableData
import eu.magicsk.transi.data.models.REGIONAL_BUS_ID
import eu.magicsk.transi.data.models.StopsState
import eu.magicsk.transi.data.models.TripDetailState
import eu.magicsk.transi.data.models.isLive
import eu.magicsk.transi.data.models.liveBoardStop
import eu.magicsk.transi.data.models.liveConnection
import eu.magicsk.transi.data.models.partStops
import eu.magicsk.transi.data.models.plannerGson
import eu.magicsk.transi.data.models.shapeEndpoint
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.FollowedTrips
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.repository.LiveBoard
import eu.magicsk.transi.util.readCachedStops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

/**
 * The trip detail's journey (from the activity's [EXTRA_JOURNEY]), completed while the screen is started: the stops of
 * R-API legs, the legs' live delays from their boarding stops' boards, and the rides' road shapes; and the trip followed
 * in the live notification while it is this journey.
 */
@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    followedTrips: FollowedTrips,
) : ViewModel() {
    private val mutableState = MutableLiveData(
        savedState.get<String>(EXTRA_JOURNEY)?.let { json ->
            try {
                plannerGson.fromJson(json, Journey::class.java)
            } catch (_: JsonParseException) {
                null
            }
        }?.takeIf { it.parts.isNotEmpty() }?.let { TripDetailState(it) }
    )
    /** Null when the screen was opened without a journey. */
    val state: LiveData<TripDetailState?> = mutableState

    // The stops list joins B-API stops to imhd stop ids and finds the boards of the boarding stops.
    private val stops = viewModelScope.async(Dispatchers.IO) { readCachedStops(context).orEmpty().toList() }
    /** The boarding stop's board of each leg leaving within the hour. */
    private val boards = mutableMapOf<Int, LiveBoard>()
    /** The vehicle followed on each leg, so a reopened board never picks the line's next departure. */
    private val busIDs = mutableMapOf<Int, String>()
    private val requestedShapes = mutableSetOf<String>()
    private var liveUpdates: Job? = null

    init {
        mutableState.value?.journey?.let(::requestShapes)
        viewModelScope.launch {
            followedTrips.trip.collect { trip ->
                update { copy(followed = trip?.takeIf { it.journey.sameRidesKey == journey.sameRidesKey }) }
            }
        }
    }

    /** Loads missing stops and follows the legs leaving soon, re-evaluated every minute; [stop] ends the live updates. */
    fun start() {
        val state = mutableState.value ?: return
        if (liveUpdates != null) return
        state.journey.parts.forEachIndexed { index, part ->
            if (part.tripId != null && part.stops == null && state.stopsState[index] != StopsState.LOADING) loadStops(index)
        }
        liveUpdates = viewModelScope.launch {
            while (isActive) {
                updateTrackers()
                delay(60_000)
            }
        }
    }

    /** Keeps the last known delays: a reopened board only lists a vehicle still at the boarding stop. */
    fun stop() {
        liveUpdates?.cancel()
        liveUpdates = null
        boards.values.forEach(LiveBoard::close)
        boards.clear()
    }

    fun loadStops(index: Int) {
        val part = mutableState.value?.journey?.parts?.getOrNull(index) ?: return
        val tripId = part.tripId ?: return
        update { copy(stopsState = stopsState + (index to StopsState.LOADING)) }
        viewModelScope.launch {
            val trip = idsbk.token()?.let { repository.getTripStops(it, tripId).data }
            val stopList = stops.await()
            val legStops = trip?.let { withContext(Dispatchers.Default) { it.partStops(part, stopList) } }
            if (trip == null || legStops == null) {
                update { copy(stopsState = stopsState + (index to StopsState.FAILED)) }
                return@launch
            }
            update {
                copy(
                    journey = journey.copy(parts = journey.parts.mapIndexed { i, p -> if (i == index) p.copy(stops = legStops) else p }),
                    stopsState = stopsState - index,
                    // The B-API reports the run on the road now, whatever its date, so it is this leg's only while live.
                    tripDelays = if (part.isLive(Instant.now(), trip.trip_delay)) tripDelays + (index to trip.trip_delay) else tripDelays,
                )
            }
            mutableState.value?.journey?.let(::requestShapes)
        }
    }

    fun toggleExpanded(index: Int) = update {
        copy(expanded = if (index in expanded) expanded - index else expanded + index)
    }

    // ponytail: once per screen for each ride whose stops all have a position, so reopening a journey fetches its
    // shapes again; an app-wide cache if that shows. Offline or on an error the ride stays straight.
    private fun requestShapes(journey: Journey) {
        journey.parts.mapNotNull { it.shapeEndpoint }.filter(requestedShapes::add).forEach { endpoint ->
            viewModelScope.launch {
                repository.getTimetableShape(endpoint).data?.let { shape -> update { copy(shapes = shapes + (endpoint to shape)) } }
            }
        }
    }

    // ponytail: one socket per leg leaving within the hour; share a board per stop if journeys get longer.
    private suspend fun updateTrackers() {
        val now = Instant.now()
        update { copy(now = now) }
        val state = mutableState.value ?: return
        val stopList: List<Stop> = stops.await()
        state.journey.parts.forEachIndexed { index, part ->
            if (part.isWalk) return@forEachIndexed
            val isDue = part.isLive(now, state.knownDelaySeconds(index))
            if (isDue && index !in boards) {
                val target = part.liveBoardStop(stopList) ?: return@forEachIndexed
                boards[index] = LiveBoard(repository, idsbk, viewModelScope).apply {
                    // Until the whole board loaded, a vehicle missing from it may only be missing from the regional
                    // rows, which could take the followed vehicle's place.
                    onDepartures = { rows ->
                        if (hasWholeBoard(target.platform ?: -1)) {
                            onConnection(index, part.liveConnection(rows, target.platform, target.scheduled, busIDs[index]))
                        }
                    }
                    setStop(target.stop)
                    connect()
                }
            } else if (!isDue) {
                boards.remove(index)?.close()
            }
        }
    }

    /**
     * The leg's departure on its board: the first vehicle matched live, which it follows from then on. A regional row
     * standing in for a timetable one names no vehicle, so it is never followed.
     */
    private fun onConnection(index: Int, connection: MHDTableData?) {
        if (connection?.type == "online" && connection.busID != REGIONAL_BUS_ID) busIDs[index] = connection.busID
        // Once our vehicle has left the board, keep its last known delay rather than none.
        if (connection == null && busIDs[index] != null) return
        update {
            if (liveConnections[index] == connection) this
            else copy(liveConnections = if (connection == null) liveConnections - index else liveConnections + (index to connection))
        }
    }

    private inline fun update(change: TripDetailState.() -> TripDetailState) {
        val state = mutableState.value ?: return
        val next = state.change()
        if (next != state) mutableState.value = next
    }

    override fun onCleared() = stop()

    companion object {
        const val EXTRA_JOURNEY = "journey"
    }
}
