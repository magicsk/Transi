package eu.magicsk.transi.data.remote

import eu.magicsk.transi.data.remote.responses.StopsJSON
import eu.magicsk.transi.data.remote.responses.TripPlannerJSON
import retrofit2.http.GET
import retrofit2.http.Query

interface ApiRequests {
//    @GET("/trip?time=1625044709988&from=Technick%C3%A9%20sklo&to=Are%C3%A1l%20vod.%20%C5%A1portov")

    @GET("trip")
    suspend fun getTrip(
        @Query("time") time: Long,
        @Query("from") from: String,
        @Query("to") to: String,
        @Query("ad") ad: Int
    ): TripPlannerJSON

    @GET("stops")
    suspend fun getStops(): StopsJSON
}