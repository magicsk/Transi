package eu.magicsk.transi.repository

import dagger.hilt.android.scopes.ActivityScoped
import eu.magicsk.transi.data.remote.ApiRequests
import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.TripPlannerJSON
import eu.magicsk.transi.util.Resource
import javax.inject.Inject

@ActivityScoped
class DataRepository @Inject constructor(
    private val api: ApiRequests
) {

    suspend fun getTrip(time: Long, from: String, to: String): Resource<TripPlannerJSON> {
        val response = try {
            api.getTrip(time, from, to)
        } catch(e: Exception) {
            return Resource.Error("An unknown error occurred.")
        }
        return Resource.Success(response)
    }

    suspend fun getStops(): Resource<StopsJSON> {
        val response = try {
            api.getStops()
        } catch(e: Exception) {
            return Resource.Error("An unknown error occurred.")
        }
        return Resource.Success(response)
    }
}