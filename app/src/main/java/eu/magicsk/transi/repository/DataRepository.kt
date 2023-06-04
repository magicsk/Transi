package eu.magicsk.transi.repository

import dagger.hilt.android.scopes.ActivityScoped
import eu.magicsk.transi.data.remote.ApiRequests
import eu.magicsk.transi.data.remote.GithubRequests
import eu.magicsk.transi.data.remote.IdsbkRequests
import eu.magicsk.transi.data.remote.ImhdRequests
import eu.magicsk.transi.data.remote.responses.ReleaseInfo
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.data.remote.responses.StopsVersion
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

    suspend fun getTrip(
        v: Int,
        from: String,
        to: String,
        date: String,
        time: String,
        arrivalDeparture: Int,
        features: String,
        preference: Int,
        moreTimeForTransfer: Int,
        transportType: String,
        rate: String,
        carriers: String,
        service: String,
        format: Int
    ): Resource<String> {
        val response = try {
            imhdApi.getTrip(
                v,
                from,
                to,
                date,
                time,
                arrivalDeparture,
                features,
                preference,
                moreTimeForTransfer,
                transportType,
                rate,
                carriers,
                service,
                format
            )
        } catch (e: Exception) {
            println(e)
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getIdsbkTrip(
        from: Int,
        to: Int,
        arrivalDeparture: Int = 0,
        hoursOfSearch: Int = 1,
        org: Int = 120,
        maxTransfers: Int = 5,
        maxWalkingDuration: Int = 10,
    ): Resource<Journeys> {
        val response = try {
            idsbkApi.getIdsbkTrip(
                from,
                to,
                arrivalDeparture,
                hoursOfSearch,
                org,
                maxTransfers,
                maxWalkingDuration,
            )
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetables(city: Int = 12): Resource<Timetables> {
        val response = try {
            idsbkApi.getTimetables(city)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetableDirections(route: Int): Resource<TimetableDirections> {
        val response = try {
            idsbkApi.getTimetableDirections(route)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetable(route: Int, arrivalDeparture: String, direction: Int, date: String): Resource<Timetable> {
        val response = try {
            idsbkApi.getTimetable(route, arrivalDeparture, direction, date)
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }

    suspend fun getTimetableDetail(route: Int, arrivalDeparture: String, direction: Int, date: String, stop: Int): Resource<TimetableDetails> {
        val response = try {
            idsbkApi.getTimetableDetail(route, arrivalDeparture, direction, date, stop)
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

    suspend fun getReleaseInfo(): Resource<ReleaseInfo> {
        val response = try {
            githubApi.getReleaseInfo()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred. $e")
        }
        return Resource.Success(response)
    }
}