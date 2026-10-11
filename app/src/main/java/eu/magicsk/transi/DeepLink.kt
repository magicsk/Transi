package eu.magicsk.transi

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.net.URI
import java.net.URISyntaxException

/** Where a `transi://` link (iOS TransiApp.onOpenURL) or an in-app shortcut leads; [MainActivity.navigate] opens it. */
sealed interface DeepLink {
    data object Trip : DeepLink
    /** The followed trip; until there is one, just the planner. */
    data object ActiveTrip : DeepLink
    data class Table(val stopId: Int? = null, val departureId: String? = null) : DeepLink
    data class Timetable(val line: String? = null) : DeepLink
    data class Map(val stopId: Int? = null) : DeepLink
    data object Invalid : DeepLink
}

private val STOP_ID = Regex("[0-9]{1,9}")
// MHDTableData.id: "{tripId}:{platform}" on the socket board, "{tripId}:{stopId}:{seconds}" for regional rows.
private val DEPARTURE_ID = Regex("-?[0-9]{1,19}(:-?[0-9]{1,19}){1,2}")
private val LINE = Regex("[A-Za-z0-9]{1,8}")

/** `transi://{table|trip|timetable|map}[/…]`; anything malformed or unknown is [DeepLink.Invalid]. */
fun parseDeepLink(url: String?): DeepLink {
    val uri = try {
        URI(url ?: return DeepLink.Invalid)
    } catch (_: URISyntaxException) {
        return DeepLink.Invalid
    }
    if (!uri.scheme.equals("transi", ignoreCase = true)) return DeepLink.Invalid
    val path = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
    return when (uri.host?.lowercase()) {
        "trip" -> when (path) {
            emptyList<String>() -> DeepLink.Trip
            listOf("active") -> DeepLink.ActiveTrip
            else -> DeepLink.Invalid
        }
        "table" -> when {
            path.isEmpty() -> DeepLink.Table()
            path.size > 2 -> DeepLink.Invalid
            else -> {
                val stopId = path[0].toStopId()
                val departureId = path.getOrNull(1)
                if (stopId == null || (departureId != null && !DEPARTURE_ID.matches(departureId))) {
                    DeepLink.Invalid
                } else {
                    DeepLink.Table(stopId, departureId)
                }
            }
        }
        "timetable" -> when {
            path.isEmpty() -> DeepLink.Timetable()
            path.size == 1 && LINE.matches(path[0]) -> DeepLink.Timetable(path[0])
            else -> DeepLink.Invalid
        }
        "map" -> when {
            path.isEmpty() -> DeepLink.Map()
            path.size == 1 -> path[0].toStopId()?.let { DeepLink.Map(it) } ?: DeepLink.Invalid
            else -> DeepLink.Invalid
        }
        else -> DeepLink.Invalid
    }
}

private fun String.toStopId(): Int? = takeIf(STOP_ID::matches)?.toInt()?.takeIf { it > 0 }

/**
 * Whether a link that started a second MainActivity, over the app's other screens (singleTop reuses only one on top)
 * or in another app's task, goes to the task's own MainActivity instead. That re-sent link carries CLEAR_TOP, so it is
 * never re-sent again.
 */
fun reroutesLink(action: String?, flags: Int, isTaskRoot: Boolean): Boolean =
    action == Intent.ACTION_VIEW && !isTaskRoot && flags and Intent.FLAG_ACTIVITY_CLEAR_TOP == 0

/** iOS "Unable to navigate" alert for links that lead nowhere. */
class UnableToNavigateDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.deep_link_error_title)
            .setMessage(R.string.deep_link_error_message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
}
