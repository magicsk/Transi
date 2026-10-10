package eu.magicsk.transi.timetable

import android.content.Context
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.magicsk.transi.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val BRATISLAVA: ZoneId = ZoneId.of("Europe/Bratislava")

const val ARG_ROUTE_ID = "route_id"
const val ARG_SHORT_NAME = "short_name"
const val ARG_OFFLINE = "offline"
const val ARG_DIRECTION_ID = "direction_id"
const val ARG_DIRECTION_NAME = "direction_name"
const val ARG_STATION_ID = "station_id"
const val ARG_STATION_NAME = "station_name"
const val ARG_STOP_CODE = "stop_code"
const val ARG_DATE = "date"

enum class TimetableCategory(@StringRes val title: Int) {
    TRAMS(R.string.trams),
    TROLLEYBUSES(R.string.trolleybuses),
    BUSES(R.string.buses),
    NIGHT_LINES(R.string.night_lines),
    TRAINS(R.string.trains),
    REGIONAL_BUSES(R.string.regional_buses);

    companion object {
        /** GTFS route_type → section; the offline database reports regional buses as 700. */
        fun of(routeType: Int, shortName: String): TimetableCategory = when (routeType) {
            0 -> TRAMS
            2, in 100..199 -> TRAINS
            3 -> if (shortName.startsWith("N")) NIGHT_LINES else BUSES
            11, 800, 50 -> if (shortName.startsWith("N")) NIGHT_LINES else TROLLEYBUSES
            else -> REGIONAL_BUSES
        }
    }
}

/**
 * Order of line short names within a section, the IDS BK list's: by the leading number (names without one last),
 * then by name. Applied to both sources, so the offline list matches the online one.
 */
val LINE_ORDER: Comparator<String> =
    compareBy({ name -> name.takeWhile { it in '0'..'9' }.toIntOrNull() ?: Int.MAX_VALUE }, { it })

/** `yyyyMMdd`, the date format of the IDS BK API and of GTFS. */
fun LocalDate.serviceDate(): String = format(DateTimeFormatter.BASIC_ISO_DATE)

/** `HH:mm` of [minutes] after the service day's midnight (GTFS noon minus 12 h, as on iOS); 1440 and later wrap. */
// padStart keeps ASCII digits whatever the device locale (String.format would localise them).
fun formatServiceTime(minutes: Int): String =
    "${(minutes / 60 % 24).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

/** Index of the first trip departing after [now] (minute of day), else the first trip. */
fun nextTripIndex(departures: List<Int>, now: LocalTime): Int =
    departures.indexOfFirst { it > now.hour * 60 + now.minute }.coerceAtLeast(0)

/** Rows of `HH` to its zero-padded minutes, in the order the hours first appear; hour = (t / 60) % 24. */
fun groupByHour(minutes: List<Int>): List<Pair<String, List<String>>> =
    minutes.groupBy { it / 60 % 24 }.map { (hour, times) ->
        hour.toString().padStart(2, '0') to times.map { (it % 60).toString().padStart(2, '0') }
    }

/** Text of the download state, the percentage only with [withProgress]; null when there is nothing to report. */
fun TimetableDownloadState.text(context: Context, withProgress: Boolean = true): String? = when (this) {
    TimetableDownloadState.Idle, TimetableDownloadState.Ready -> null
    TimetableDownloadState.Checking -> context.getString(R.string.timetable_db_checking)
    is TimetableDownloadState.Downloading -> percent?.takeIf { withProgress }
        ?.let { context.getString(R.string.timetable_db_downloading_progress, it) }
        ?: context.getString(R.string.timetable_db_downloading)
    TimetableDownloadState.Decompressing -> context.getString(R.string.timetable_db_decompressing)
    is TimetableDownloadState.Error -> context.getString(message)
}

/** Shows the dialog until the fragment's view is destroyed, so it never outlives the screen; without a view, nothing. */
fun Fragment.showWhileViewExists(builder: MaterialAlertDialogBuilder) {
    val lifecycle = viewLifecycleOwnerLiveData.value?.lifecycle ?: return
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) return
    val dialog = builder.show()
    lifecycle.addObserver(LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
    })
}

/**
 * A screen opened from the offline database goes back to the line list once that database is no longer the source
 * (deleted, turned off or closed for an update); the list reloads from the current source. True when it left.
 */
fun Fragment.leftClosedOfflineDatabase(offline: Boolean, offlineSource: Boolean): Boolean {
    if (!offline || offlineSource) return false
    findNavController().popBackStack(R.id.navigationTimetables, false)
    return true
}

fun Fragment.showTimetableError(@StringRes title: Int, onRetry: () -> Unit, onCancel: () -> Unit) =
    showWhileViewExists(
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(R.string.timetable_error_message)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
            .setPositiveButton(R.string.timetable_retry) { _, _ -> onRetry() }
    )
