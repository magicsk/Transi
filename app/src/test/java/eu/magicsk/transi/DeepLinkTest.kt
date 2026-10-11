package eu.magicsk.transi

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinkTest {
    private fun assertLink(expected: DeepLink, url: String?) = assertEquals(url, expected, parseDeepLink(url))

    @Test
    fun tabs() {
        assertLink(DeepLink.Trip, "transi://trip")
        assertLink(DeepLink.ActiveTrip, "transi://trip/active")
        assertLink(DeepLink.Table(), "transi://table")
        assertLink(DeepLink.Table(), "transi://table/")
        assertLink(DeepLink.Timetable(), "transi://timetable")
        assertLink(DeepLink.Map(), "transi://map")
        assertLink(DeepLink.Trip, "TRANSI://TRIP")
    }

    @Test
    fun tableStopAndDeparture() {
        assertLink(DeepLink.Table(93), "transi://table/93")
        assertLink(DeepLink.Table(93, "1234:2"), "transi://table/93/1234:2")
        assertLink(DeepLink.Table(93, "-5:93:1760000000"), "transi://table/93/-5:93:1760000000")
    }

    @Test
    fun lineAndMapStop() {
        assertLink(DeepLink.Timetable("N61"), "transi://timetable/N61")
        assertLink(DeepLink.Map(93), "transi://map/93")
    }

    @Test
    fun linkOverOtherScreensGoesToTheRootOnce() {
        assertTrue(reroutesLink(Intent.ACTION_VIEW, Intent.FLAG_ACTIVITY_NEW_TASK, isTaskRoot = false))
        // Already re-sent (or a notification's CLEAR_TOP link, which never stacks a second one).
        assertFalse(reroutesLink(Intent.ACTION_VIEW, Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP, false))
        assertFalse(reroutesLink(Intent.ACTION_VIEW, Intent.FLAG_ACTIVITY_NEW_TASK, isTaskRoot = true))
        assertFalse(reroutesLink(Intent.ACTION_MAIN, 0, isTaskRoot = false))
        assertFalse(reroutesLink(null, 0, isTaskRoot = false))
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            null, "", "not a url", "https://imhd.sk/table/93", "transi:table", "transi://", "transi://search",
            "transi://trip/1", "transi://trip/active/1",
            "transi://table/abc", "transi://table/0", "transi://table/-3", "transi://table/+3", "transi://table/99999999999",
            "transi://table/93/abc", "transi://table/93/1234", "transi://table/93/1:2/3",
            "transi://timetable/N%2061", "transi://timetable/../x", "transi://timetable/1/2",
            "transi://map/abc", "transi://map/1.5", "transi://map/1/2",
        ).forEach { assertLink(DeepLink.Invalid, it) }
    }
}
