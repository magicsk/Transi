package eu.magicsk.transi.util

import eu.magicsk.transi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of the iOS scripts/check-changelog-parsing.sh cases. */
class WhatsNewTest {
    @Test
    fun parsesReleaseNotesLikeIos() {
        val release = parseChangelog(
            """
            ## Features
            - core models and database
            - offline support and UI

            ## Bugfixes
            - timezone bug and `arrival_time` column

            ## Empty

            """.trimIndent()
        )
        assertEquals("Headings without items are dropped", listOf("Features", "Bugfixes"), release.map { it.title })
        assertEquals(
            "Bullet markers are stripped and the first letter is capitalized",
            listOf("Core models and database", "Offline support and UI"),
            release[0].items
        )
        assertEquals("Inline markdown is kept for rendering", listOf("Timezone bug and `arrival_time` column"), release[1].items)
        assertEquals(listOf(R.drawable.ic_auto_awesome, R.drawable.ic_bug_report), release.map { it.icon })

        val other = parseChangelog("Plain note\r\n### Other Changes\r\n- misc")
        assertEquals("Text before the first heading is dropped and CRLF splits lines", listOf("Other Changes"), other.map { it.title })
        assertEquals(listOf("Misc"), other[0].items)
        assertEquals("Unknown headings get a neutral icon", listOf(R.drawable.ic_pending), other.map { it.icon })
        assertEquals(listOf(R.drawable.ic_bolt), parseChangelog("## Improvements\n- faster").map { it.icon })

        assertTrue("Empty notes produce nothing to show", parseChangelog("\n## Features\n\n").isEmpty())
    }
}
