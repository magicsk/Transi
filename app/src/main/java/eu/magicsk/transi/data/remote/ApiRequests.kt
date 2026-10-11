package eu.magicsk.transi.data.remote

import eu.magicsk.transi.data.models.TimetableShape
import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.data.remote.responses.StopsVersion
import eu.magicsk.transi.data.remote.responses.TimetableManifest
import retrofit2.http.GET
import retrofit2.http.Url

interface ApiRequests {
    @GET("stops")
    suspend fun getStops(): Stops

    @GET("stops?v")
    suspend fun getStopsVersion(): StopsVersion

    @GET("timetable/manifest")
    suspend fun getTimetableManifest(): TimetableManifest

    /** [endpoint] from `timetableShapeEndpoint`, already encoded as iOS sends it. */
    @GET
    suspend fun getTimetableShape(@Url endpoint: String): TimetableShape
}