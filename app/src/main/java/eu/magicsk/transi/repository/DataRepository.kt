package eu.magicsk.transi.repository

import dagger.hilt.android.scopes.ActivityScoped
import eu.magicsk.transi.data.models.BApiTrip
import eu.magicsk.transi.data.models.TicketCatalogue
import eu.magicsk.transi.data.models.TimetableShape
import eu.magicsk.transi.data.remote.ApiRequests
import eu.magicsk.transi.data.remote.GithubRequests
import eu.magicsk.transi.data.remote.IdsbkRequests
import eu.magicsk.transi.data.remote.ImhdRequests
import eu.magicsk.transi.data.remote.responses.ReleaseInfo
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.data.remote.responses.StopsVersion
import eu.magicsk.transi.data.remote.responses.TimetableManifest
import eu.magicsk.transi.data.remote.responses.idsbk.*
import eu.magicsk.transi.util.Resource
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@ActivityScoped
class DataRepository @Inject constructor(
    private val api: ApiRequests,
    private val imhdApi: ImhdRequests,
    private val idsbkApi: IdsbkRequests,
    private val githubApi: GithubRequests
) {

    suspend fun getTrip(from: String, to: String, date: String, time: String, arrival: Boolean): Resource<String> =
        call { imhdApi.getTrip(from = from, to = to, date = date, time = time, arrivalDeparture = if (arrival) 1 else 0) }

    suspend fun getIdsbkSession(): Resource<Session> = call { idsbkApi.getIdsbkSession() }

    suspend fun getRaptorTrip(xSession: String, body: RaptorRequest): Resource<RaptorResponse> =
        call { idsbkApi.getRaptorTrip(body, xSession) }

    suspend fun getTimetables(xSession: String, city: Int = 12): Resource<Timetables> =
        call { idsbkApi.getTimetables(city, xSession) }

    suspend fun getTimetableDirections(xSession: String, route: Int): Resource<TimetableDirections> =
        call { idsbkApi.getTimetableDirections(route, xSession) }

    suspend fun getTimetable(xSession: String, route: Int, arrivalDeparture: String, direction: Int, date: String): Resource<Timetable> =
        call { idsbkApi.getTimetable(route, arrivalDeparture, direction, date, xSession) }

    suspend fun getTimetableDetail(xSession: String, route: Int, arrivalDeparture: String, direction: Int, date: String, stop: Int): Resource<TimetableDetails> =
        call { idsbkApi.getTimetableDetail(route, arrivalDeparture, direction, date, stop, xSession) }

    suspend fun getStationDepartures(xSession: String, stationId: Int, date: String, minutes: Int): Resource<RegionalDepartures> =
        call { idsbkApi.getStationDepartures(stationId, date, minutes, xSession) }

    suspend fun getTicketTypes(xSession: String, cacheKey: String): Resource<TicketCatalogue> =
        call { idsbkApi.getTicketTypes(cacheKey, xSession) }

    suspend fun getTripStops(xSession: String, tripId: Int): Resource<BApiTrip> = call { idsbkApi.getTrip(tripId, xSession) }

    suspend fun getTimetableShape(endpoint: String): Resource<TimetableShape> = call { api.getTimetableShape(endpoint) }

    suspend fun getStops(): Resource<Stops> = call { api.getStops() }

    suspend fun getStopsVersion(): Resource<StopsVersion> = call { api.getStopsVersion() }

    suspend fun getTimetableManifest(): Resource<TimetableManifest> = call { api.getTimetableManifest() }

    suspend fun getReleaseInfo(): Resource<ReleaseInfo> = call { githubApi.getReleaseInfo() }

    // A failed request becomes an Error; cancellation propagates, so a cancelled caller stops instead of handling an error.
    private inline fun <T> call(request: () -> T): Resource<T> = try {
        Resource.Success(request())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Resource.Error("An unknown error occurred. $e")
    }
}
