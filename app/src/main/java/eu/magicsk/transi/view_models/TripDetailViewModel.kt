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
import eu.magicsk.transi.data.models.Part
import eu.magicsk.transi.data.models.StopsState
import eu.magicsk.transi.data.models.TimetableShape
import eu.magicsk.transi.data.models.TripDetailState
import eu.magicsk.transi.data.models.isLive
import eu.magicsk.transi.data.models.partStops
import eu.magicsk.transi.data.models.path
import eu.magicsk.transi.data.models.plannerGson
import eu.magicsk.transi.data.models.shapeEndpoint
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.idsbk.StopGps
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.util.readCachedStops
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * The trip detail's journey (from the activity's [EXTRA_JOURNEY]), completed while the screen is started: the stops of
 * R-API legs, the legs' live delays from their boarding stops' boards, and the rides' road shapes.
 */
@HiltViewModel
class TripDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
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

    /** Counts the ride shapes loaded for this screen; the map redraws its lines when it moves. */
    val shapesLoaded = MutableLiveData(0)

    // The stops list joins B-API stops to imhd stop ids and finds the boards of the boarding stops.
    private val stops = viewModelScope.async(Dispatchers.IO) { readCachedStops(context).orEmpty().toList() }
    private val trackers = mutableMapOf<Int, PartLiveTracker>()
    /** The vehicle followed on each leg, so a restarted tracker never picks the line's next departure. */
    private val busIDs = mutableMapOf<Int, String>()
    private val requestedShapes = mutableSetOf<String>()
    private var liveUpdates: Job? = null

    init {
        mutableState.value?.journey?.let(::requestShapes)
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

    /** Keeps the last known delays: a restarted tracker only finds a vehicle still at the boarding stop. */
    fun stop() {
        liveUpdates?.cancel()
        liveUpdates = null
        trackers.values.forEach(PartLiveTracker::stop)
        trackers.clear()
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

    /** The ride along the roads and tracks once its shape loaded; null while straight. */
    fun shapedPath(part: Part): List<StopGps>? {
        val shape = part.shapeEndpoint?.let(shapes::get) ?: return null
        return shape.path(part.stops.orEmpty().mapNotNull { it.gps })
    }

    // Once per screen for each ride whose stops all have a position; kept for the app run. Offline or on an error the
    // ride stays straight.
    private fun requestShapes(journey: Journey) {
        journey.parts.mapNotNull { it.shapeEndpoint }.filter { !shapes.containsKey(it) && requestedShapes.add(it) }.forEach { endpoint ->
            viewModelScope.launch {
                repository.getTimetableShape(endpoint).data?.let {
                    shapes[endpoint] = it
                    shapesLoaded.value = (shapesLoaded.value ?: 0) + 1
                }
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
            if (isDue && index !in trackers) {
                PartLiveTracker.start(part, stopList, busIDs[index], repository, idsbk, viewModelScope) { connection ->
                    if (connection?.type == "online") busIDs[index] = connection.busID
                    // Once our vehicle has left the board, keep its last known delay rather than none.
                    if (connection == null && busIDs[index] != null) return@start
                    update {
                        if (liveConnections[index] == connection) this
                        else copy(liveConnections = if (connection == null) liveConnections - index else liveConnections + (index to connection))
                    }
                }?.let { trackers[index] = it }
            } else if (!isDue) {
                trackers.remove(index)?.stop()
            }
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

        /** Ride shapes by request, kept while the app runs. */
        private val shapes = ConcurrentHashMap<String, TimetableShape>()
    }
}
