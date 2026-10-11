package eu.magicsk.transi.view_models

import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.BuildConfig
import eu.magicsk.transi.R
import eu.magicsk.transi.data.models.ArrivalDeparture
import eu.magicsk.transi.data.models.CACHED_JOURNEY_LIMIT
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.RECENT_SEARCH_LIMIT
import eu.magicsk.transi.data.models.RecentTripSearch
import eu.magicsk.transi.data.models.decodeRecentSearches
import eu.magicsk.transi.data.models.encodeRecentSearches
import eu.magicsk.transi.data.models.remember
import eu.magicsk.transi.data.models.restorable
import eu.magicsk.transi.data.models.searchId
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.timetable.OfflineRoutingEngine
import eu.magicsk.transi.util.FetchSource
import eu.magicsk.transi.util.PagingDates
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.cepoDate
import eu.magicsk.transi.util.clock
import eu.magicsk.transi.util.intPref
import eu.magicsk.transi.util.isActualLocation
import eu.magicsk.transi.util.mapCepoJourneys
import eu.magicsk.transi.util.mapRaptorJourneys
import eu.magicsk.transi.util.mergeJourneys
import eu.magicsk.transi.util.nearest
import eu.magicsk.transi.util.nextPagingDates
import eu.magicsk.transi.util.pagingDatesFor
import eu.magicsk.transi.util.raptorRequest
import eu.magicsk.transi.util.raptorTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject

enum class TripPlannerError { NO_ROUTES, NO_CONNECTION, NO_LOCATION }

data class TripPlannerState(
    val from: Stop? = null,
    val to: Stop? = null,
    val arrivalDeparture: ArrivalDeparture = ArrivalDeparture.DEPARTURE,
    val date: Instant = Instant.now(),
    val customDate: Boolean = false,
    /** The current search's journeys; null before results and after an error. */
    val journeys: List<Journey>? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: TripPlannerError? = null,
    /** Newest first. */
    val recents: List<RecentTripSearch> = emptyList(),
    /** Changes with every new search or shown recent, whose list starts at the top. */
    val generation: Int = 0,
) {
    val searchId get() = searchId(from, to)
    val criteria get() = if (from != null && to != null) RecentTripSearch(from, to, arrivalDeparture, date, customDate) else null
}

/**
 * The trip planner: both online sources, or for the first page the offline timetables when that is on; paging and
 * recent searches. Activity-scoped, shared with the stop pickers.
 */
@HiltViewModel
class TripPlannerViewModel @Inject constructor(
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    private val offlineRouting: OfflineRoutingEngine,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
) : ViewModel() {
    /** Read by the type-ahead as it closes, to put the stop's name back into its field. */
    val selectedFromStop = MutableLiveData<Stop?>()
    val selectedToStop = MutableLiveData<Stop?>()

    private var current = TripPlannerState()
    private val stateData = MutableLiveData(current)
    val state: LiveData<TripPlannerState> = stateData

    private val prefs = Prefs.get(context)
    private val actualLocation = Stop(0, 0, context.getString(R.string.actual_position), "", "location", 0, 0.0, 0.0, null)
    private var stops: List<Stop> = emptyList()
    private var hasLocation = false
    private var recentsLoaded = false
    private var persistJob: Job? = null
    private var offlineJob: Job? = null

    /** A search waiting for the stops list, the location or a stop; true when it goes into the recents. */
    private var pendingSearch: Boolean? = null
    /** Bumped by every change of criteria and every search; answers to an older one are dropped. */
    private var generation = 0
    private var paging = PagingDates(null, null)
    private val pagingSources = mutableSetOf<FetchSource>()

    init {
        val killedSearch = decodeRecentSearches(savedStateHandle[SAVED_SEARCH]).firstOrNull()
        viewModelScope.launch {
            val (stored, savedCacheId) = withContext(Dispatchers.IO) {
                decodeRecentSearches(prefs.getString(Prefs.TRIP_RECENT_SEARCHES, null)) to
                    prefs.getString(Prefs.TRIP_SEARCH_ID, null)
            }
            onRecentsLoaded(stored, savedCacheId, killedSearch)
        }
    }

    fun setStops(value: List<Stop>) {
        stops = value
        runPendingSearch()
    }

    /** The first location fix fills an empty "From" with the actual location. */
    fun onLocationAvailable() {
        hasLocation = true
        if (current.from == null) setState { copy(from = actualLocation) }
        runPendingSearch()
    }

    /**
     * iOS fetchTripToActualStop (map Directions): to [stop], from Actual location when [located] (there is a fix),
     * otherwise from the "From" already set.
     */
    fun planFromActualLocationTo(stop: Stop, located: Boolean) {
        setState { copy(from = if (located) actualLocation else from, to = stop) }
        requestSearch()
    }

    /** From the stop pickers: picking a stop runs the search. */
    fun setSelectedFromStop(value: Stop) {
        setState { copy(from = value) }
        requestSearch()
    }

    fun setSelectedToStop(value: Stop?) {
        setState { copy(to = value) }
        requestSearch()
    }

    fun swap() {
        setState { copy(from = to, to = from) }
        requestSearch()
    }

    fun setArrivalDeparture(value: ArrivalDeparture) {
        if (value == current.arrivalDeparture) return
        setState { copy(arrivalDeparture = value) }
        if (!currentSearchMatchesCriteria()) requestSearch()
    }

    fun setDate(value: Instant) {
        setState { copy(date = value, customDate = true) }
        requestSearch()
    }

    fun resetDateToNow() {
        setState { copy(date = Instant.now(), customDate = false) }
        requestSearch()
    }

    /** A recent search with its cached journeys, or searched again. */
    fun showRecentSearch(id: String) {
        val search = current.recents.firstOrNull { it.id == id } ?: return
        open(search, search.trip)
    }

    /** Infinite scroll: the last journey of a source on screen loads that source's next page. */
    fun loadMoreIfNeeded(journey: Journey) {
        val journeys = current.journeys ?: return
        if (journey.id == journeys.lastOrNull { it.isRaptor }?.id) fetch(FetchSource.MORE_RAPTOR)
        if (journey.id == journeys.lastOrNull { it.isImhd }?.id) fetch(FetchSource.MORE_IMHD)
    }

    private fun currentSearchMatchesCriteria(): Boolean {
        val search = current.recents.firstOrNull { it.id == current.searchId } ?: return false
        return search.arrivalDeparture == current.arrivalDeparture &&
            (!current.customDate || search.arrivalDepartureDate == current.date)
    }

    /** The criteria changed: answers still on their way are for the old ones. */
    private fun requestSearch(record: Boolean = true) {
        generation++
        pagingSources.clear()
        pendingSearch = record
        if (!runPendingSearch() && (current.loading || current.loadingMore)) {
            setState { copy(loading = false, loadingMore = false) }
        }
    }

    /** Runs the pending search once there are both stops, the stops list and, for the actual location, a fix. */
    private fun runPendingSearch(): Boolean {
        val record = pendingSearch ?: return false
        val from = current.from ?: return false
        val to = current.to ?: return false
        if (stops.nearest() == null) return false
        if ((from.isActualLocation || to.isActualLocation) && !hasLocation) {
            setState { copy(journeys = null, loading = false, loadingMore = false, error = TripPlannerError.NO_LOCATION) }
            return false
        }
        pendingSearch = null
        search(record)
        return true
    }

    private fun search(record: Boolean) {
        var next = current
        if (record) {
            if (!next.customDate) next = next.copy(date = Instant.now())
            val criteria = next.criteria ?: return
            next = next.copy(recents = next.recents.remember(criteria))
        }
        generation++
        pagingSources.clear()
        paging = PagingDates(next.date, next.date)
        setState { next.copy(journeys = null, loading = true, loadingMore = false, error = null, generation = generation) }
        if (record) persistRecents()
        fetch(FetchSource.INITIAL)
    }

    /** A search with its searched time, also one that ran "now". */
    private fun open(search: RecentTripSearch, trip: List<Journey>?) {
        generation++
        pagingSources.clear()
        paging = pagingDatesFor(trip.orEmpty())
        setState {
            copy(
                from = search.from,
                to = search.to,
                arrivalDeparture = search.arrivalDeparture,
                date = search.arrivalDepartureDate,
                customDate = true,
                journeys = trip,
                loading = false,
                loadingMore = false,
                error = null,
                generation = generation,
            )
        }
        if (trip == null) requestSearch(record = false)
    }

    private fun fetch(source: FetchSource, allowOffline: Boolean = true) {
        val search = current
        val nearest = stops.nearest()
        val fromStop = search.from?.let { if (it.isActualLocation) nearest else it } ?: return
        val toStop = search.to?.let { if (it.isActualLocation) nearest else it } ?: return
        if (source != FetchSource.INITIAL && !pagingSources.add(source)) return
        // As iOS: with the offline planner on and its database ready, the first page is planned offline; paging stays online.
        if (source == FetchSource.INITIAL && allowOffline && offlineRouting.isReady &&
            prefs.getBoolean(Prefs.OFFLINE_TRIP_PLANNER, false)
        ) {
            fetchOffline(search, fromStop, toStop)
            return
        }

        val withRaptor = source != FetchSource.MORE_IMHD && BuildConfig.R_API_KEY.isNotEmpty()
        val withImhd = source != FetchSource.MORE_RAPTOR
        val raptorDate = if (source == FetchSource.INITIAL) search.date else paging.raptor ?: search.date
        val imhdDate = if (source == FetchSource.INITIAL) search.date else paging.imhd ?: search.date
        val stopsSnapshot = stops
        val fetchGeneration = generation
        if (source != FetchSource.INITIAL) setState { copy(loadingMore = true) }

        viewModelScope.launch {
            val raptor = async {
                (if (withRaptor) idsbk.token() else null)?.let {
                    val body = raptorRequest(
                        fromStop.station_id, toStop.station_id, raptorDate, search.arrivalDeparture,
                        prefs.intPref(Prefs.TRIP_MAX_WALK_DURATION, Prefs.TRIP_MAX_WALK_DURATION_DEFAULT),
                        prefs.intPref(Prefs.TRIP_MAX_TRANSFERS, Prefs.TRIP_MAX_TRANSFERS_DEFAULT),
                    )
                    repository.raptorTrip(it, body)
                }
            }
            val imhd = async {
                if (withImhd) {
                    val arrival = search.arrivalDeparture == ArrivalDeparture.ARRIVAL
                    repository.getTrip("g${fromStop.id}", "g${toStop.id}", cepoDate(imhdDate), clock(imhdDate), arrival).data
                } else {
                    null
                }
            }
            val raptorResponse = raptor.await()
            val imhdResponse = imhd.await()
            val (raptorJourneys, imhdJourneys) = withContext(Dispatchers.Default) {
                mapRaptorJourneys(raptorResponse?.journey.orEmpty()) to imhdResponse?.let { mapCepoJourneys(it, stopsSnapshot) }
            }
            if (fetchGeneration != generation) return@launch
            pagingSources.remove(source)
            onResults(source, raptorResponse != null, raptorJourneys, imhdJourneys)
        }
    }

    private fun fetchOffline(search: TripPlannerState, fromStop: Stop, toStop: Stop) {
        val fetchGeneration = generation
        offlineJob?.cancel()
        offlineJob = viewModelScope.launch {
            val journeys = offlineRouting.plan(
                fromStop, toStop, search.date, search.arrivalDeparture,
                prefs.intPref(Prefs.TRIP_MAX_TRANSFERS, Prefs.TRIP_MAX_TRANSFERS_DEFAULT),
                prefs.intPref(Prefs.TRIP_MAX_WALK_DURATION, Prefs.TRIP_MAX_WALK_DURATION_DEFAULT),
            )
            if (fetchGeneration != generation) return@launch
            when {
                // Null when the database closed meanwhile (deleted or being updated) or lacks a stop: online after all.
                journeys == null -> fetch(FetchSource.INITIAL, allowOffline = false)
                // The timetables answered without journeys, as iOS shows "no journeys"; no connection failed.
                journeys.isEmpty() -> setState {
                    copy(journeys = null, loading = false, loadingMore = false, error = TripPlannerError.NO_ROUTES)
                }
                // Their o- ids page neither online source.
                else -> onResults(FetchSource.INITIAL, raptorAnswered = false, raptor = journeys, imhd = null)
            }
        }
    }

    private fun onResults(source: FetchSource, raptorAnswered: Boolean, raptor: List<Journey>, imhd: List<Journey>?) {
        paging = nextPagingDates(source, paging, raptorAnswered, imhd != null, imhd.orEmpty())
        val found = raptor + imhd.orEmpty()
        if (source == FetchSource.INITIAL && found.isEmpty()) {
            // Every source failed, rather than answered without journeys.
            val error = if (!raptorAnswered && imhd == null) TripPlannerError.NO_CONNECTION else TripPlannerError.NO_ROUTES
            setState { copy(journeys = null, loading = false, loadingMore = false, error = error) }
            return
        }
        val journeys = mergeJourneys(if (source == FetchSource.INITIAL) emptyList() else current.journeys.orEmpty(), found)
        setState { copy(journeys = journeys, loading = false, loadingMore = pagingSources.isNotEmpty(), error = null) }
        cacheJourneys(journeys)
    }

    /** The current search's recent keeps its first journeys. */
    private fun cacheJourneys(journeys: List<Journey>) {
        val index = current.recents.indexOfFirst { it.id == current.searchId }
        if (index < 0) return
        val recents = current.recents.toMutableList()
        recents[index] = recents[index].copy(trip = journeys.take(CACHED_JOURNEY_LIMIT), tripSavedAt = Instant.now())
        setState { copy(recents = recents) }
        persistRecents(cacheId = recents[index].cacheID)
    }

    private fun onRecentsLoaded(stored: List<RecentTripSearch>, savedCacheId: String?, killedSearch: RecentTripSearch?) {
        val made = current.recents
        val saveDuration = prefs.intPref(Prefs.TRIP_SAVE_DURATION, Prefs.TRIP_SAVE_DURATION_DEFAULT)
        val restorable = stored.restorable(saveDuration, Instant.now()).filter { old -> made.none { it.id == old.id } }
        recentsLoaded = true
        setState { copy(recents = (made + restorable).take(RECENT_SEARCH_LIMIT)) }
        if (made.isNotEmpty()) persistRecents()

        val from = current.from
        if (generation != 0 || current.to != null || (from != null && !from.isActualLocation)) return
        if (killedSearch != null) {
            // Back after the process was killed: that search at its time, with its journeys whatever the save duration.
            val trip = stored.firstOrNull { it.cacheID == killedSearch.cacheID }?.trip
            open(killedSearch, trip)
            return
        }
        val restored = current.recents.firstOrNull { it.cacheID == savedCacheId } ?: current.recents.firstOrNull() ?: return
        if (restored.trip != null) {
            open(restored, restored.trip)
        } else {
            setState {
                copy(
                    from = restored.from,
                    to = restored.to,
                    arrivalDeparture = restored.arrivalDeparture,
                    date = restored.arrivalDepartureDate,
                    customDate = true,
                )
            }
        }
    }

    /** Writes the recents in order, off the main thread, once the stored ones are read. */
    private fun persistRecents(cacheId: String? = null) {
        if (!recentsLoaded) return
        val recents = current.recents
        val previous = persistJob
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            previous?.join()
            val json = encodeRecentSearches(recents)
            prefs.edit {
                putString(Prefs.TRIP_RECENT_SEARCHES, json)
                if (cacheId != null) putString(Prefs.TRIP_SEARCH_ID, cacheId)
            }
        }
    }

    private fun setState(transform: TripPlannerState.() -> TripPlannerState) {
        val old = current
        current = old.transform()
        stateData.value = current
        if (current.from != old.from) selectedFromStop.value = current.from
        if (current.to != old.to) selectedToStop.value = current.to
        val criteria = current.criteria
        if (criteria != old.criteria) savedStateHandle[SAVED_SEARCH] = criteria?.let { encodeRecentSearches(listOf(it)) }
    }

    private companion object {
        /** The current search's criteria, to restore it after the process is killed. */
        const val SAVED_SEARCH = "trip_planner_search"
    }
}
