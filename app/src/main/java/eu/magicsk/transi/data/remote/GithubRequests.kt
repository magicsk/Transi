package eu.magicsk.transi.data.remote

import eu.magicsk.transi.data.remote.responses.ReleaseInfo
import retrofit2.http.GET

interface GithubRequests {
    @GET("repos/magicsk/Transi/releases/latest")
    suspend fun getReleaseInfo(): ReleaseInfo
}