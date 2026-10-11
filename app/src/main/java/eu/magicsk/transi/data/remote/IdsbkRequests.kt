package eu.magicsk.transi.data.remote

import android.os.Parcelable
import eu.magicsk.transi.BuildConfig
import eu.magicsk.transi.data.models.BApiTrip
import eu.magicsk.transi.data.models.TicketCatalogue
import eu.magicsk.transi.data.remote.responses.idsbk.*
import kotlinx.parcelize.Parcelize
import retrofit2.http.*

@Parcelize
data class SessionRequest(
    private val installation: String = "e674cc00-c509-4c08-8e04-c82d51f8f884",
    private val language: String = "en"
    ): Parcelable

interface IdsbkRequests {

    @POST("startup")
    suspend fun getIdsbkSession(
        @Body sessionRequestBody: SessionRequest = SessionRequest(),
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
        @Header("Accept-Encoding") acceptEncoding: String = "gzip",
        @Header("content-type") contentType: String = "application/json",
        @Header("Host") host: String = "api.idsbk.sk",
    ): Session

    // The R-API address stays out of the source, like its key.
    @POST
    suspend fun getRaptorTrip(
        @Body body: RaptorRequest,
        @Header("x-session") xSession: String,
        @Url url: String = "${BuildConfig.R_API_URL.trimEnd('/')}/mobile/v1/raptor/",
        @Header("x-api-key") apiKey: String = BuildConfig.R_API_KEY,
        @Header("Content-Type") contentType: String = "application/json",
        @Header("User-Agent") userAgent: String = "ProdProd/204 CFNetwork/3826.600.41 Darwin/24.6.0",
    ): RaptorResponse

    /** The ticket catalogue; [cacheKey] is the cached catalogue's key, or "null" without one. */
    @GET("ticket/types/{cacheKey}")
    suspend fun getTicketTypes(
        @Path("cacheKey") cacheKey: String,
        @Header("X-Session") xSession: String,
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
    ): TicketCatalogue

    @GET("station/{station}/timetable/{date}/{minutes}/1")
    suspend fun getStationDepartures(
        @Path("station") stationId: Int,
        @Path("date") date: String,
        @Path("minutes") minutes: Int,
        @Header("X-Session") xSession: String,
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
    ): RegionalDepartures

    /** A run's stops and its delay, for the trip detail. */
    @GET("trip/{tripId}/")
    suspend fun getTrip(
        @Path("tripId") tripId: Int,
        @Header("X-Session") xSession: String,
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
    ): BApiTrip

    @GET("route/{city}/")
    suspend fun getTimetables(
        @Path("city") city: Int,
        @Header("X-Session") xSession: String = "",
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
        @Header("Origin") origin: String = "https://api.idsbk.sk",
        @Header("DNT") DNT: Int = 1,
        @Header("Referer") referer: String = "https://api.idsbk.sk",
        @Header("Sec-Fetch-Dest") sfd: String = "empty",
        @Header("Sec-Fetch-Mode") sfm: String = "cors",
        @Header("Sec-Fetch-Site") sfs: String = "same-site",
        @Header("Sec-GPC") secGPC: String = "1",
        @Header("Pragma") pragma: String = "no-cache",
        @Header("Cache-Control") cacheControl: String = "no-cache",
    ): Timetables

    @GET("route/{route}/directions")
    suspend fun getTimetableDirections(
        @Path("route") route: Int,
        @Header("X-Session") xSession: String = "",
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
        @Header("Origin") origin: String = "https://api.idsbk.sk",
        @Header("DNT") DNT: Int = 1,
        @Header("Referer") referer: String = "https://api.idsbk.sk",
        @Header("Sec-Fetch-Dest") sfd: String = "empty",
        @Header("Sec-Fetch-Mode") sfm: String = "cors",
        @Header("Sec-Fetch-Site") sfs: String = "same-site",
        @Header("Sec-GPC") secGPC: String = "1",
        @Header("Pragma") pragma: String = "no-cache",
        @Header("Cache-Control") cacheControl: String = "no-cache",
    ): TimetableDirections

    @GET("route/{route}/{ad}/{direction}/{date}/0/1440/")
    suspend fun getTimetable(
        @Path("route") route: Int,
        @Path("ad") arrivalDeparture: String,
        @Path("direction") direction: Int,
        @Path("date") date: String,
        @Header("X-Session") xSession: String = "",
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
        @Header("Origin") origin: String = "https://api.idsbk.sk",
        @Header("DNT") DNT: Int = 1,
        @Header("Referer") referer: String = "https://api.idsbk.sk",
        @Header("Sec-Fetch-Dest") sfd: String = "empty",
        @Header("Sec-Fetch-Mode") sfm: String = "cors",
        @Header("Sec-Fetch-Site") sfs: String = "same-site",
        @Header("Sec-GPC") secGPC: String = "1",
        @Header("Pragma") pragma: String = "no-cache",
        @Header("Cache-Control") cacheControl: String = "no-cache",
    ): Timetable

    @GET("route/{route}/{ad}/{direction}/{date}/{stop}/")
    suspend fun getTimetableDetail(
        @Path("route") route: Int,
        @Path("ad") arrivalDeparture: String,
        @Path("direction") direction: Int,
        @Path("date") date: String,
        @Path("stop") stop: Int,
        @Header("X-Session") xSession: String = "",
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
        @Header("Origin") origin: String = "https://api.idsbk.sk",
        @Header("DNT") DNT: Int = 1,
        @Header("Referer") referer: String = "https://api.idsbk.sk",
        @Header("Sec-Fetch-Dest") sfd: String = "empty",
        @Header("Sec-Fetch-Mode") sfm: String = "cors",
        @Header("Sec-Fetch-Site") sfs: String = "same-site",
        @Header("Sec-GPC") secGPC: String = "1",
        @Header("Pragma") pragma: String = "no-cache",
        @Header("Cache-Control") cacheControl: String = "no-cache",
    ): TimetableDetails
}