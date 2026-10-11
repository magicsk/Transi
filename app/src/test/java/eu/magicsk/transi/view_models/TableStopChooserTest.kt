package eu.magicsk.transi.view_models

import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.util.nearestStopId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TableStopChooserTest {
    private fun stop(id: Int) = Stop(id, id, "Stop $id", "", "bus", 0, 0.0, 0.0, null)
    private val unsorted = listOf(stop(10), stop(20), stop(30))
    // The same stops nearest first.
    private val sorted = listOf(stop(30), stop(10), stop(20))

    @Test
    fun locationBeforeStopsShowsNearestOnceStopsLoad() {
        val chooser = TableStopChooser { -1 }
        chooser.onLocationAvailable(true)
        // The fix came first: MainViewModel keeps it and sorts the list as soon as /stops returns.
        chooser.onStops(sorted, located = true, downloaded = true)
        assertEquals(30, chooser.current?.id)
        assertTrue(chooser.follow)
    }

    @Test
    fun cachedStopsBeforeLocationWaitThenShowNearest() {
        val chooser = TableStopChooser { -1 }
        chooser.onLocationAvailable(true)
        chooser.onStops(unsorted, located = false, downloaded = false)
        chooser.onStopsChecked()
        assertNull(chooser.current)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(30, chooser.current?.id)
    }

    @Test
    fun downloadedStopsWithoutFixShowFirstStopThenFollowTheFix() {
        val chooser = TableStopChooser { -1 }
        chooser.onLocationAvailable(true)
        chooser.onStops(unsorted, located = false, downloaded = true)
        assertEquals(10, chooser.current?.id)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(30, chooser.current?.id)
    }

    @Test
    fun deniedLocationFallsBackInEitherOrder() {
        val deniedFirst = TableStopChooser { 20 }
        deniedFirst.onLocationAvailable(false)
        assertNull(deniedFirst.current)
        deniedFirst.onStops(unsorted, located = false, downloaded = false)
        assertEquals(20, deniedFirst.current?.id)
        assertFalse(deniedFirst.follow)

        val stopsFirst = TableStopChooser { -1 }
        stopsFirst.onStops(unsorted, located = false, downloaded = false)
        assertNull(stopsFirst.current)
        stopsFirst.onLocationAvailable(false)
        assertEquals(10, stopsFirst.current?.id)
        assertTrue(stopsFirst.follow)
    }

    @Test
    fun defaultStopStopsFollowingUntilActualLocationIsPicked() {
        val chooser = TableStopChooser { 20 }
        chooser.onLocationAvailable(true)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(20, chooser.current?.id)
        assertFalse(chooser.follow)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(20, chooser.current?.id)
        chooser.change(-1)
        assertEquals(30, chooser.current?.id)
        assertTrue(chooser.follow)
    }

    @Test
    fun restoredStopKeepsNotFollowing() {
        val chooser = TableStopChooser(10) { 20 }
        chooser.onLocationAvailable(true)
        chooser.onStops(sorted, located = true, downloaded = true)
        assertEquals(10, chooser.current?.id)
        assertFalse(chooser.follow)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(10, chooser.current?.id)
    }

    @Test
    fun restoredFollowBeatsDefaultStop() {
        val chooser = TableStopChooser(-1) { 20 }
        chooser.onLocationAvailable(true)
        chooser.onStops(sorted, located = true, downloaded = true)
        assertEquals(nearestStopId(sorted), chooser.current?.id)
        assertTrue(chooser.follow)
    }

    @Test
    fun stopPickedBeforeStopsLoadIsShownWithThem() {
        val chooser = TableStopChooser { -1 }
        chooser.change(20)
        assertNull(chooser.current)
        chooser.onLocationAvailable(true)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(20, chooser.current?.id)
        assertFalse(chooser.follow)
    }

    @Test
    fun missingDefaultStopFollowsNearest() {
        val chooser = TableStopChooser { 99 }
        chooser.onLocationAvailable(true)
        chooser.onStops(sorted, located = true, downloaded = false)
        assertEquals(30, chooser.current?.id)
        assertTrue(chooser.follow)
    }
}
