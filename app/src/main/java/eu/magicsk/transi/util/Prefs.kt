package eu.magicsk.transi.util

import android.content.Context
import android.content.SharedPreferences

/** The app's settings file (covered by the backup rules) and every key stored in it. */
object Prefs {
    const val FILE = "Transi"

    const val STOPS_VERSION = "stopsVersion"
    /** Stops JSON kept in prefs before the cache moved to files/stops.json; migrated once, then removed. */
    const val LEGACY_STOP_LIST = "stopList"
    const val MAGIC_API_URL_OVERRIDE = "magic_api_url_override"
    const val CHANGELOG_VERSION = "changelog_version"

    const val DISPLAY_TABLE_CLOCK = "display_table_clock"
    const val DISPLAY_SOCKET_STATUS = "display_socket_status"
    const val DEFAULT_STOP_ID = "default_stop_id"
    const val FAVORITE_STOP_IDS = "favorite_stop_ids"
    /** String set of the service info texts already read; replaced the JSON in "readInfoTexts". */
    const val READ_INFO_TEXTS = "read_info_texts"

    /** Boolean, default false: use the downloaded timetable database and keep it updated. */
    const val OFFLINE_TIMETABLES = "offline_timetables"

    /** SHA-256 of the installed gzipped base.db and schedule.db; absent when not installed. */
    const val TIMETABLE_BASE_DB_SHA = "timetable_base_db_sha"
    const val TIMETABLE_SCHEDULE_DB_SHA = "timetable_schedule_db_sha"

    const val TRIP_MAX_WALK_DURATION = "trip_max_walk_duration"
    const val TRIP_MAX_WALK_DURATION_DEFAULT = 15
    const val TRIP_MAX_TRANSFERS = "trip_max_transfers"
    const val TRIP_MAX_TRANSFERS_DEFAULT = 3
    /** Hours to keep cached trips: -1 forever, 0 until the app restarts. */
    const val TRIP_SAVE_DURATION = "trip_save_duration"
    const val TRIP_SAVE_DURATION_DEFAULT = -1
    /** JSON list of [eu.magicsk.transi.data.models.RecentTripSearch], newest first. */
    const val TRIP_RECENT_SEARCHES = "trip_recent_searches"
    /** cacheID of the search whose trips were cached last. */
    const val TRIP_SEARCH_ID = "trip_search_id"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
