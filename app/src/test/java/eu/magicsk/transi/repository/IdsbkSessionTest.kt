package eu.magicsk.transi.repository

import eu.magicsk.transi.data.remote.IdsbkRequests
import eu.magicsk.transi.data.remote.SessionRequest
import eu.magicsk.transi.data.remote.responses.idsbk.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.create
import java.io.IOException

class IdsbkSessionTest {
    @Test
    fun callersShareOneRequestAndTheNextRetriesAFailure(): Unit = runBlocking {
        var requests = 0
        var answer = CompletableDeferred<Session>()
        // Never called except for the startup request; Retrofit only builds their proxies.
        val unused = Retrofit.Builder().baseUrl("http://localhost/").build()
        val idsbk = object : IdsbkRequests by unused.create<IdsbkRequests>() {
            override suspend fun getIdsbkSession(
                sessionRequestBody: SessionRequest, apiKey: String, acceptEncoding: String, contentType: String, host: String,
            ): Session {
                requests++
                return answer.await()
            }
        }
        val session = IdsbkSession(DataRepository(unused.create(), unused.create(), idsbk, unused.create()))

        val first = async { session.token() }
        val second = async { session.token() }
        yield()
        answer.completeExceptionally(IOException("offline"))
        assertNull(first.await())
        assertNull(second.await())
        assertEquals(1, requests)

        answer = CompletableDeferred(Session("token"))
        assertEquals("token", session.token())
        assertEquals("token", session.token())
        assertEquals(2, requests)
    }
}
