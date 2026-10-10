package eu.magicsk.transi.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonParseException
import eu.magicsk.transi.R
import eu.magicsk.transi.data.remote.responses.Stop
import eu.magicsk.transi.data.remote.responses.Stops
import java.io.File
import java.io.IOException

private fun stopsFile(context: Context) = File(context.filesDir, "stops.json")

/** The cached stop list, moved once from the old "stopList" preference into files/stops.json. Call off the main thread. */
fun readCachedStops(context: Context): Stops? {
    val file = stopsFile(context)
    return try {
        if (file.exists()) {
            file.bufferedReader().use { Gson().fromJson(it, Stops::class.java) }
        } else {
            val prefs = Prefs.get(context)
            val legacy = prefs.getString(Prefs.LEGACY_STOP_LIST, null) ?: return null
            Gson().fromJson(legacy, Stops::class.java)?.also { stops ->
                if (saveCachedStops(context, stops)) prefs.edit { remove(Prefs.LEGACY_STOP_LIST) }
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: JsonParseException) {
        null
    }?.takeIf { it.isNotEmpty() }
}

/** Writes the list through a temporary file so a failed write keeps the previous cache. Call off the main thread. */
fun saveCachedStops(context: Context, stops: List<Stop>): Boolean {
    val file = stopsFile(context)
    val temp = File(context.filesDir, "stops.json.tmp")
    val saved = try {
        temp.bufferedWriter().use { Gson().toJson(stops, it) }
        temp.renameTo(file)
    } catch (_: IOException) {
        false
    } catch (_: JsonIOException) {
        // Gson wraps a failed write, such as a full disk, in this unchecked exception.
        false
    }
    if (!saved) temp.delete()
    return saved
}

fun sortedByDistance(stops: List<Stop>, location: Location): List<Stop> {
    val result = FloatArray(1)
    return stops.map { stop ->
        Location.distanceBetween(location.latitude, location.longitude, stop.lat, stop.lng, result)
        stop to result[0]
    }.sortedBy { it.second }.map { it.first }
}

/** iOS GlobalController.getNearestStopId: the first real stop of the distance-sorted list. */
fun nearestStopId(stops: List<Stop>): Int = stops.firstOrNull { it.id > 0 }?.id ?: 20

fun Context.hasLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

fun favoriteStopIds(context: Context): List<Int> =
    Prefs.get(context)
        .getString(Prefs.FAVORITE_STOP_IDS, "").orEmpty()
        .split(',').mapNotNull { it.toIntOrNull() }

fun toggleFavoriteStop(context: Context, stopId: Int) {
    val ids = favoriteStopIds(context).toMutableList()
    if (!ids.remove(stopId)) ids.add(stopId)
    Prefs.get(context).edit {
        putString(Prefs.FAVORITE_STOP_IDS, ids.joinToString(","))
    }
}

/** Icon and tint for a stop type, as on iOS: bus red, regional bus yellow, train and location blue. */
fun stopTypeIcon(type: String?): Pair<Int, Int> = when (type) {
    "bus" -> R.drawable.ic_bus to R.color.table_red
    "regio_bus" -> R.drawable.ic_regiobus to R.color.table_yellow
    "train" -> R.drawable.ic_train to R.color.table_blue
    "location" -> R.drawable.ic_location_on to R.color.table_blue
    "map" -> R.drawable.ic_map to R.color.table_green
    else -> R.drawable.ic_bus to R.color.table_grey
}
