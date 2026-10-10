package eu.magicsk.transi.view_models

import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.util.nearestStopId

/**
 * Which stop the table shows (iOS StopsListProvider default/nearest rules and VirtualTableController.changeStop),
 * kept free of Android types so the order in which stops and the location arrive can be tested.
 */
class TableStopChooser(
    /** The table stop before process death: a stop id, or -1 when it followed the nearest stop. */
    private var restoredStopId: Int? = null,
    private val defaultStopId: () -> Int,
) {
    /** All stops, nearest first once [located]. */
    var stops: List<Stop> = emptyList()
        private set
    /** Null until the stops and the location (or the default stop) are known. */
    var current: Stop? = null
        private set
    var follow = true
        private set
    /** Location allowed and on; null until the permission prompt is answered. */
    var locationAvailable: Boolean? = null
        private set
    private var located = false
    private var restoredFollow = false

    /** A new stop list, sorted by distance when [located]; [downloaded] when /stops just returned it. */
    fun onStops(sorted: List<Stop>, located: Boolean, downloaded: Boolean) {
        if (sorted.isEmpty()) return
        stops = sorted
        this.located = located
        when (val restored = restoredStopId) {
            null -> Unit
            -1 -> restoredFollow = true
            else -> change(restored)
        }
        restoredStopId = null
        when {
            located -> showNearestStop()
            downloaded || locationAvailable == false -> setDefaultStopIfNeeded()
        }
    }

    fun onLocationAvailable(available: Boolean) {
        locationAvailable = available
        if (!available) setDefaultStopIfNeeded()
    }

    /** The /stops?v check answered; without location the default stop is due now. */
    fun onStopsChecked() {
        if (locationAvailable == false) setDefaultStopIfNeeded()
    }

    /** -1 follows the nearest stop; any other stop turns following off unless [switchOnly]. */
    fun change(stopId: Int, switchOnly: Boolean = false) {
        if (stopId == -1) {
            setFollow(true)
            return
        }
        if (current?.id == stopId) return
        val stop = stops.firstOrNull { it.id == stopId } ?: return
        if (!switchOnly) follow = false
        current = stop
    }

    fun setFollow(follow: Boolean) {
        this.follow = follow
        if (follow) change(nearestStopId(stops), switchOnly = true)
    }

    // Waits for a stop list, so the table never shows a placeholder stop.
    private fun setDefaultStopIfNeeded() {
        if (current == null && stops.isNotEmpty()) showDefaultStop()
    }

    // A valid default turns off nearest-stop following, so later location fixes keep it.
    private fun showDefaultStop() {
        // Following before process death beats the default stop once.
        val defaultStopId = if (restoredFollow) -1 else defaultStopId()
        restoredFollow = false
        if (defaultStopId > 0 && stops.any { it.id == defaultStopId }) {
            change(defaultStopId)
        } else {
            change(nearestStopId(stops), switchOnly = true)
        }
    }

    private fun showNearestStop() {
        if (!follow || !located) return
        if (current == null) showDefaultStop() else change(nearestStopId(stops), switchOnly = true)
    }
}
