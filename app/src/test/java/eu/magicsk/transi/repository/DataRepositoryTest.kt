package eu.magicsk.transi.repository

import eu.magicsk.transi.data.remote.GithubRequests
import eu.magicsk.transi.data.remote.responses.ReleaseInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.create

class DataRepositoryTest {
    @Test
    fun cancelledCallPropagatesCancellation(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val github = object : GithubRequests {
            override suspend fun getReleaseInfo(): ReleaseInfo {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        // Never called; Retrofit only builds their proxies.
        val unused = Retrofit.Builder().baseUrl("http://localhost/").build()
        val repository = DataRepository(unused.create(), unused.create(), unused.create(), github)

        var continued = false
        val caller = launch {
            repository.getReleaseInfo()
            continued = true
        }
        started.await()
        caller.cancelAndJoin()
        assertFalse("a cancelled call returned instead of throwing CancellationException", continued)
    }
}
