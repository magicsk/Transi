package eu.magicsk.transi.data.remote

import eu.magicsk.transi.data.remote.responses.Stops
import eu.magicsk.transi.data.remote.responses.StopsVersion
import eu.magicsk.transi.data.remote.responses.TimetableManifest
import retrofit2.http.GET

interface ApiRequests {
    @GET("stops")
    suspend fun getStops(): Stops

    @GET("stops?v")
    suspend fun getStopsVersion(): StopsVersion

    @GET("timetable/manifest")
    suspend fun getTimetableManifest(): TimetableManifest
}