package eu.magicsk.transi.util

import android.content.Context
import android.content.SharedPreferences

/** The app's settings file (covered by the backup rules) and every key stored in it. */
object Prefs {
    const val FILE = "Transi"

    const val STOP_LIST = "stopList"
    const val STOPS_VERSION = "stopsVersion"
    const val MAGIC_API_URL_OVERRIDE = "magic_api_url_override"

    /** Boolean, default false: use the downloaded timetable database and keep it updated. */
    const val OFFLINE_TIMETABLES = "offline_timetables"

    /** SHA-256 of the installed gzipped base.db and schedule.db; absent when not installed. */
    const val TIMETABLE_BASE_DB_SHA = "timetable_base_db_sha"
    const val TIMETABLE_SCHEDULE_DB_SHA = "timetable_schedule_db_sha"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
