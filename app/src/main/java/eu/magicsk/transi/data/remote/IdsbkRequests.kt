package eu.magicsk.transi.data.remote

import android.os.Parcelable
import eu.magicsk.transi.BuildConfig
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

    @FormUrlEncoded
    @POST("search")
    suspend fun getIdsbkTrip(
        @Field("from_station_id") from: Int,
        @Field("to_station_id") to: Int,
        @Field("search_from") arrivalDeparture: Int,
        @Field("search_to_hours") hoursOfSearch: Int,
        @Field("org_id") org: Int,
        @Field("max_transfers") maxTransfers: Int,
        @Field("max_walk_duration") maxWalkingDuration: Int,
        @Header("X-API-Key") apiKey: String = BuildConfig.IDSBK_API_KEY,
        @Header("X-Session") xSession: String = "",
        @Header("Origin") origin: String = "https://api.idsbk.sk",
        @Header("DNT") DNT: Int = 1,
        @Header("Referer") referer: String = "https://api.idsbk.sk",
        @Header("Sec-Fetch-Dest") sfd: String = "empty",
        @Header("Sec-Fetch-Mode") sfm: String = "cors",
        @Header("Sec-Fetch-Site") sfs: String = "same-site",
        @Header("Sec-GPC") secGPC: String = "1",
        @Header("Pragma") pragma: String = "no-cache",
        @Header("Cache-Control") cacheControl: String = "no-cache",
    ): Journeys

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

    @GET("route/{route}/{ad}/{direction}/{date}/0/1440")
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

    @GET("route/{route}/{ad}/{direction}/{date}/{stop}")
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