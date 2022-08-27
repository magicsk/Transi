package eu.magicsk.transi.repository

import dagger.hilt.android.scopes.ActivityScoped
import eu.magicsk.transi.data.remote.ApiRequests
import eu.magicsk.transi.data.remote.GithubRequests
import eu.magicsk.transi.data.remote.ImhdRequests
import eu.magicsk.transi.data.remote.responses.ReleaseInfo
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsVersion
import eu.magicsk.transi.util.Resource
import javax.inject.Inject

@ActivityScoped
class DataRepository @Inject constructor(
    private val api: ApiRequests,
    private val imhdApi: ImhdRequests,
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
            return Resource.Error("An unknown error occurred.")
        }
        return Resource.Success(response)
    }

    suspend fun getStops(): Resource<StopsJSON> {
        val response = try {
            api.getStops()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred.")
        }
        return Resource.Success(response)
    }

    suspend fun getStopsVersion(): Resource<StopsVersion> {
        val response = try {
            api.getStopsVersion()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred.")
        }
        return Resource.Success(response)
    }

    suspend fun getReleaseInfo(): Resource<ReleaseInfo> {
        val response = try {
            githubApi.getReleaseInfo()
        } catch (e: Exception) {
            return Resource.Error("An unknown error occurred.")
        }
        return Resource.Success(response)
    }
}