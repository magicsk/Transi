package eu.magicsk.transi.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class MagicApiUrlTest {
    private val original = "https://api.magicsk.eu/stops?v".toHttpUrl()

    @Test
    fun emptyOrInvalidOverrideKeepsUrl() {
        assertEquals(original, magicApiUrl(original, null))
        assertEquals(original, magicApiUrl(original, ""))
        assertEquals(original, magicApiUrl(original, "not a url"))
        assertEquals(original, magicApiUrl(original, "ftp://10.0.2.2:3000"))
        val elsewhere = "https://cdn.example/base.db.gz".toHttpUrl()
        assertEquals(elsewhere, magicApiUrl(elsewhere, "http://10.0.2.2:3000"))
    }

    @Test
    fun overrideReplacesSchemeHostAndPort() {
        assertEquals("http://10.0.2.2:3000/stops?v", magicApiUrl(original, "http://10.0.2.2:3000").toString())
    }

    @Test
    fun overridePathPrefixesOriginalPath() {
        assertEquals("https://dev.example/api/stops?v", magicApiUrl(original, "https://dev.example/api").toString())
        assertEquals("https://dev.example/api/stops?v", magicApiUrl(original, "https://dev.example/api/").toString())
    }
}
