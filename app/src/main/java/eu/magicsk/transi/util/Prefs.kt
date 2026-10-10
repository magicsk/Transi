package eu.magicsk.transi.util

import android.content.Context
import android.content.SharedPreferences

/** The app's settings file (covered by the backup rules) and every key stored in it. */
object Prefs {
    const val FILE = "Transi"

    const val STOP_LIST = "stopList"
    const val STOPS_VERSION = "stopsVersion"
    const val MAGIC_API_URL_OVERRIDE = "magic_api_url_override"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
