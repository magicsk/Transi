package eu.magicsk.transi.repository

import dagger.hilt.android.scopes.ActivityScoped
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

@ActivityScoped
class DataRepository @Inject constructor(
    private val api: ApiRequests,
    private val imhdApi: ImhdRequests,
    private val idsbkApi: IdsbkRequests,
    private val githubApi: GithubRequests
) {

    suspend fun getTrip(from: String, to: String, date: String, time: String, arrival: Boolean): Resource<String> {
        val response = try {
            imhdApi.getTrip(from = from, to = to, date = date, time = time, arrivalDeparture = if (arrival) 1 else 0)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getIdsbkSession(): Resource<Session> {
        val response = try {
            idsbkApi.getIdsbkSession()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getRaptorTrip(xSession: String, body: RaptorRequest): Resource<RaptorResponse> {
        val response = try {
            idsbkApi.getRaptorTrip(body, xSession)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetables(xSession: String, city: Int = 12): Resource<Timetables> {
        val response = try {
            idsbkApi.getTimetables(city, xSession)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetableDirections(xSession: String, route: Int): Resource<TimetableDirections> {
        val response = try {
            idsbkApi.getTimetableDirections(route, xSession)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetable(xSession: String, route: Int, arrivalDeparture: String, direction: Int, date: String): Resource<Timetable> {
        val response = try {
            idsbkApi.getTimetable(route, arrivalDeparture, direction, date, xSession)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetableDetail(xSession: String, route: Int, arrivalDeparture: String, direction: Int, date: String, stop: Int): Resource<TimetableDetails> {
        val response = try {
            idsbkApi.getTimetableDetail(route, arrivalDeparture, direction, date, stop, xSession)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getStationDepartures(xSession: String, stationId: Int, date: String, minutes: Int): Resource<RegionalDepartures> {
        val response = try {
            idsbkApi.getStationDepartures(stationId, date, minutes, xSession)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getStops(): Resource<Stops> {
        val response = try {
            api.getStops()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getStopsVersion(): Resource<StopsVersion> {
        val response = try {
            api.getStopsVersion()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetableManifest(): Resource<TimetableManifest> {
        val response = try {
            api.getTimetableManifest()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getReleaseInfo(): Resource<ReleaseInfo> {
        val response = try {
            githubApi.getReleaseInfo()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }
}