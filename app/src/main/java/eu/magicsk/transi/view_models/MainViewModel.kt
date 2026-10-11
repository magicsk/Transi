package eu.magicsk.transi.view_models

import android.content.Context
import android.location.Location
import androidx.core.content.edit
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.DeepLink
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.util.Prefs
import eu.magicsk.transi.util.readCachedStops
import eu.magicsk.transi.util.saveCachedStops
import eu.magicsk.transi.util.sortedByDistance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** All stops, nearest first once the location is known. */
    val stopList = MutableLiveData<Stops>()
    val actualLocation = MutableLiveData<Location>()
    /** Location permission granted and location turned on; null until the permission prompt is answered. */
    val locationAvailable = MutableLiveData<Boolean?>()
    /** The stop the table shows; null until the stops and the location (or the default stop) are known. */
    val tableStop = MutableLiveData<Stop?>()
    /** The table follows the nearest stop (iOS changeLocation). */
    val followLocation = MutableLiveData(true)

    /**
     * The last link (iOS pendingNavigation), set by MainActivity.navigate: the table expands its departure, the Map tab
     * selects its stop and the Timetables tab opens its line once they can, then [consumeLink] it.
     */
    val pendingLink = MutableLiveData<DeepLink?>()

    private val idsbkSessionData = MutableLiveData<Session?>()
    val idsbkSession: MutableLiveData<Session?> = idsbkSessionData

    private val prefs = Prefs.get(context)
    private val chooser = TableStopChooser(savedState[SAVED_TABLE_STOP_ID]) { prefs.getInt(Prefs.DEFAULT_STOP_ID, -1) }
    private var unmodifiedStops: List<Stop> = emptyList()
    private var lastLocation: Location? = null
    private var publishJob: Job? = null

    // A location fix and the stop list can arrive in either order; whichever comes second picks the stop.
    // A fix goes out after the stops sorted for it, so its observers read the nearest stop for that fix.
    fun setActualLocation(value: Location) {
        lastLocation = value
        if (unmodifiedStops.isEmpty()) actualLocation.value = value else publishStops()
    }

    fun setLocationAvailable(value: Boolean) {
        locationAvailable.value = value
        choose { chooser.onLocationAvailable(value) }
    }

    /** Clears [link] once its tab carried it out, unless another link replaced it meanwhile. */
    fun consumeLink(link: DeepLink) {
        if (pendingLink.value === link) pendingLink.value = null
    }

    /** -1 follows the nearest stop; any other stop turns following off. */
    fun changeTableStop(stopId: Int) = choose { chooser.change(stopId) }

    fun setFollowLocation(follow: Boolean) = choose { chooser.setFollow(follow) }

    private fun choose(action: () -> Unit) {
        action()
        // Kept across process death: the stop picked by hand, or null while following the nearest stop.
        chooser.current?.let { savedState[SAVED_TABLE_STOP_ID] = if (chooser.follow) -1 else it.id }
        if (followLocation.value != chooser.follow) followLocation.value = chooser.follow
        if (tableStop.value !== chooser.current) tableStop.value = chooser.current
    }

    private fun publishStops(downloaded: Boolean = false) {
        val stops = unmodifiedStops
        if (stops.isEmpty()) return
        val location = lastLocation
        publishJob?.cancel()
        publishJob = viewModelScope.launch {
            val sorted = if (location == null) stops else withContext(Dispatchers.Default) { sortedByDistance(stops, location) }
            stopList.value = Stops().apply { addAll(sorted) }
            if (location != null && actualLocation.value !== location) actualLocation.value = location
            choose { chooser.onStops(sorted, located = location != null, downloaded = downloaded) }
        }
    }

    // iOS StopsListProvider.fetchStops: a new list only when /stops?v changes, saved before its version.
    private suspend fun fetchStops(cached: Boolean) {
        val version = repository.getStopsVersion().data ?: return
        if (version.version != prefs.getString(Prefs.STOPS_VERSION, "") || !cached) {
            val stops = repository.getStops().data?.takeIf { it.isNotEmpty() }
            if (stops != null) {
                unmodifiedStops = stops
                publishStops(downloaded = true)
                if (withContext(Dispatchers.IO) { saveCachedStops(context, stops) }) {
                    prefs.edit { putString(Prefs.STOPS_VERSION, version.version) }
                }
            }
        }
        choose { chooser.onStopsChecked() }
    }

    init {
        viewModelScope.launch {
            idsbkSessionData.value = idsbk.token()?.let(::Session)
        }
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { readCachedStops(context) }
            if (cached != null) {
                unmodifiedStops = cached
                publishStops()
            }
            // Without a list nothing works, so keep trying (each pass requests again) until one loads.
            while (true) {
                fetchStops(cached != null)
                if (unmodifiedStops.isNotEmpty()) break
                delay(5_000) // ponytail: fixed 5 s like iOS, add backoff if the extra requests ever matter
            }
        }
    }

    private companion object {
        const val SAVED_TABLE_STOP_ID = "tableStopId"
    }
}
