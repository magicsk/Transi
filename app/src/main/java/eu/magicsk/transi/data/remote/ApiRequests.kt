package eu.magicsk.transi.data.remote

import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.StopsVersion
import eu.magicsk.transi.data.remote.responses.TripPlannerJSON
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiRequests {
    @GET("trip")
    suspend fun getTrip(
        @Query("time") time: Long,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("ad") ad: Int
    ): TripPlannerJSON

    @GET("stops")
    suspend fun getStops(): StopsJSON

    @GET("stops?v")
    suspend fun getStopsVersion(): StopsVersion
}