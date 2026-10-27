package eu.magicsk.transi.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun offersOnlyNewerVersions() {
        assertTrue(isNewerVersion("1.4.1", "1.4.0"))
        assertTrue(isNewerVersion("v1.10.0", "1.9.9"))
        assertTrue(isNewerVersion("2", "1.9"))
        assertTrue(isNewerVersion("1.4.0.1", "1.4"))
        assertFalse(isNewerVersion("1.4.0", "1.4.0"))
        assertFalse(isNewerVersion("v1.4", "1.4.0"))
        assertFalse(isNewerVersion("1.3.9", "1.4.0"))
        assertFalse(isNewerVersion("1.9.9", "1.10.0"))
    }

    @Test
    fun unparsableVersionsAreNotNewer() {
        assertFalse(isNewerVersion("1.5.0-beta", "1.4.0"))
        assertFalse(isNewerVersion("1.5.0", "1.4.0-debug"))
        assertFalse(isNewerVersion("", "1.4.0"))
        assertFalse(isNewerVersion("1..5", "1.4.0"))
        assertFalse(isNewerVersion("1.-5", "1.4.0"))
        assertFalse(isNewerVersion("1.+5", "1.4.0"))
        assertFalse(isNewerVersion("99999999999.0", "1.4.0"))
    }

    @Test
    fun downloadMustMatchEveryKnownSizeAndDigest() {
        val hash = "ab12"
        assertTrue(isCompleteDownload(10, hash, 10, 10, "sha256:AB12"))
        assertTrue(isCompleteDownload(10, hash, 10, -1, null))
        assertTrue(isCompleteDownload(10, hash, null, -1, null))
        // Only a sha256 digest can be checked.
        assertTrue(isCompleteDownload(10, hash, 10, 10, "md5:ffff"))
        assertFalse(isCompleteDownload(9, hash, 10, -1, null))
        assertFalse(isCompleteDownload(10, hash, 10, 11, null))
        assertFalse(isCompleteDownload(10, hash, null, 9, null))
        assertFalse(isCompleteDownload(10, hash, 10, 10, "sha256:ab13"))
        assertFalse(isCompleteDownload(10, hash, 10, 10, "sha256:"))
    }
}
