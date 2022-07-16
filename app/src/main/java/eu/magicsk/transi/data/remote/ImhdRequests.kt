package eu.magicsk.transi.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface ImhdRequests {
    @GET("cepo")
    suspend fun getTrip(
        @Query("v") v: Int = 7,
        @Query("a") from: String,
        @Query("b") to: String,
        @Query("pd") date: String = "",
        @Query("pt") time: String = "",
        @Query("pa") arrivalDeparture: Int = 0,
        @Query("pl") features: String = "pl=1.0-4.0",
        @Query("pp") preference: Int = 0,
        @Query("pc") moreTimeForTransfer: Int = 0,
        @Query("pv") transportType: String = "30.1-11.1-3.1-6.1-2.1-15.1-22.1",
        @Query("pf") rate: String = "2.1-21.1-49.1",
        @Query("po") carriers: String = "1.1-80.1-214.1-219.1",
        @Query("op") service: String = "Planner",
        @Query("format") format: Int = 0,
    ): String
}