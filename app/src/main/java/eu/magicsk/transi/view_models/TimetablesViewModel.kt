package eu.magicsk.transi.view_models

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import eu.magicsk.transi.data.remote.responses.idsbk.Departure
import eu.magicsk.transi.data.remote.responses.idsbk.Route
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import eu.magicsk.transi.data.remote.responses.idsbk.TimetableDirection
import eu.magicsk.transi.repository.DataRepository
import eu.magicsk.transi.repository.IdsbkSession
import eu.magicsk.transi.timetable.TimetableDatabase
import eu.magicsk.transi.timetable.serviceDate
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** Routes and where they came from: online route ids are IDS BK route ids; offline lines are looked up by short name. */
data class TimetableRoutes(val routes: List<Route>, val offline: Boolean)

/**
 * Timetables from the IDS BK API, or from the offline database when it is on and ready. Activity scoped, so the
 * route list is fetched once per app run (again only when the source switches between online and offline).
 * Every fetch returns null on failure.
 */
@HiltViewModel
class TimetablesViewModel @Inject constructor(
    private val repository: DataRepository,
    private val idsbk: IdsbkSession,
    private val database: TimetableDatabase,
) : ViewModel() {

    private val routesData = MutableLiveData<TimetableRoutes>()
    private val routesLoadingData = MutableLiveData(false)
    private val routesFailedData = MutableLiveData(false)
    val routes: LiveData<TimetableRoutes> = routesData
    val routesLoading: LiveData<Boolean> = routesLoadingData
    val routesFailed: LiveData<Boolean> = routesFailedData

    val downloadState = database.state
    val showDownloadBanner: Boolean get() = database.isOfflineEnabled && !database.isReady

    /** No-op while loading, while a failure waits for Retry or Cancel, and when the cached list has the right source. */
    fun loadRoutes(session: Session?) {
        val offline = database.isOfflineEnabled && database.isReady
        if (routesLoadingData.value == true || routesFailedData.value == true || routesData.value?.offline == offline) return
        routesLoadingData.value = true
        viewModelScope.launch {
            val routes = if (offline) {
                database.queryRoutes()
            } else {
                repository.getTimetables(token(session)).data?.routes
            }
            routesLoadingData.value = false
            if (routes == null) {
                routesFailedData.value = true
                return@launch
            }
            routesData.value = TimetableRoutes(routes, offline)
            // The database may have opened or closed meanwhile.
            loadRoutes(session)
        }
    }

    fun dismissRoutesError() {
        routesFailedData.value = false
    }

    suspend fun directions(routeId: Int, shortName: String, offline: Boolean, session: Session?): List<TimetableDirection>? =
        if (offline) {
            database.queryDirections(shortName)
        } else {
            repository.getTimetableDirections(token(session), routeId).data?.directions
        }

    suspend fun departures(
        routeId: Int,
        shortName: String,
        offline: Boolean,
        direction: TimetableDirection,
        date: LocalDate,
        session: Session?,
    ): List<Departure>? = if (offline) {
        database.queryDepartures(shortName, direction.direction_id, direction.direction, date)
    } else {
        repository.getTimetable(token(session), routeId, "departures", direction.direction_id, date.serviceDate())
            .data?.departures
    }

    /** Departure minutes of a direction from one stop: online by IDS BK station id, offline by GTFS stop id. */
    suspend fun stopTimes(
        routeId: Int,
        shortName: String,
        offline: Boolean,
        directionId: Int,
        directionName: String,
        date: LocalDate,
        stationId: Int,
        stopCode: String?,
        session: Session?,
    ): List<Int>? = if (offline) {
        stopCode?.let { code ->
            database.queryDepartures(shortName, directionId, directionName, date)
                ?.flatMap { trip -> trip.direction_departures.filter { it.stop_code == code }.map { it.departure } }
                ?.sorted()
        }
    } else {
        repository.getTimetableDetail(token(session), routeId, "departures", directionId, date.serviceDate(), stationId)
            .data?.departures?.map { it.t }
    }

    // The launch session is null when its startup request failed; the shared one tries again.
    private suspend fun token(session: Session?): String = session?.session ?: idsbk.token().orEmpty()
}
