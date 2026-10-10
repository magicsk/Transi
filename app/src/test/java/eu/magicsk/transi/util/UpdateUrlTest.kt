package eu.magicsk.transi.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateUrlTest {
    @Test
    fun acceptsOnlyHttpsGithubReleaseHosts() {
        assertTrue(isAllowedUpdateUrl("https://github.com/magicsk/Transi/releases/download/v1.3.3/eu.magicsk.transi.1.3.3.apk"))
        assertTrue(isAllowedUpdateUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=a%2Bb"))
        assertTrue(isAllowedUpdateUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1/2"))
        assertFalse(isAllowedUpdateUrl("http://github.com/magicsk/Transi/releases/download/v1.3.3/app.apk"))
        assertFalse(isAllowedUpdateUrl("https://github.com.evil.example/app.apk"))
        assertFalse(isAllowedUpdateUrl("https://evil.example/github.com/app.apk"))
        assertFalse(isAllowedUpdateUrl("file:///sdcard/app.apk"))
        assertFalse(isAllowedUpdateUrl("not a url"))
        assertFalse(isAllowedUpdateUrl(""))
    }

    @Test
    fun finalUrlAcceptsAnyHttpsGithubAssetHost() {
        assertTrue(isAllowedFinalUpdateUrl("https://github.com/magicsk/Transi/releases/download/v1.3.3/eu.magicsk.transi.1.3.3.apk"))
        assertTrue(isAllowedFinalUpdateUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=a%2Bb"))
        assertTrue(isAllowedFinalUpdateUrl("https://foo.githubusercontent.com/github-production-release-asset/1/2"))
        assertFalse(isAllowedUpdateUrl("https://foo.githubusercontent.com/github-production-release-asset/1/2"))
        assertFalse(isAllowedFinalUpdateUrl("https://githubusercontent.com.evil.com/app.apk"))
        assertFalse(isAllowedFinalUpdateUrl("https://foo.githubusercontent.com.evil.com/app.apk"))
        assertFalse(isAllowedFinalUpdateUrl("https://evilgithubusercontent.com/app.apk"))
        assertFalse(isAllowedFinalUpdateUrl("http://foo.githubusercontent.com/app.apk"))
        assertFalse(isAllowedFinalUpdateUrl("http://github.com/magicsk/Transi/releases/download/v1.3.3/app.apk"))
        assertFalse(isAllowedFinalUpdateUrl("https://evil.example/app.apk"))
        assertFalse(isAllowedFinalUpdateUrl("not a url"))
    }
}
