package eu.magicsk.transi.data.remote

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface ImhdRequests {
    /** The imhd trip planner (cepo). [date] is a Bratislava yyyy-MM-dd and [time] a Bratislava HH:mm. */
    @GET("cepo")
    suspend fun getTrip(
        @Query("v") v: Int = 7,
        @Query("a") from: String,
        @Query("b") to: String,
        @Query("pd") date: String,
        @Query("pt") time: String,
        @Query("pa") arrivalDeparture: Int,
        @Query("pl") features: String = "1.0-4.0",
        @Query("pp") preference: Int = 0,
        @Query("pc") moreTimeForTransfer: Int = 0,
        @Query("format") format: Int = 0,
        @Query("op") service: String = "Planner",
        @Header("User-Agent") userAgent: String =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
    ): String
}
