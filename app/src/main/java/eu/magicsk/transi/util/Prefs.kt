package eu.magicsk.transi.util

import android.content.Context
import android.content.SharedPreferences
import eu.magicsk.transi.data.models.SeasonPass

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

    /** Boolean, default true: followed departures alert with a sound. */
    const val LIVE_ACTIVITIES_SOUNDS = "live_activities_sounds"
    /** Int seconds, 30…600: a followed departure alerts only when it is less than this away. */
    const val LIVE_ACTIVITY_THRESHOLD = "live_activity_threshold"
    const val LIVE_ACTIVITY_THRESHOLD_DEFAULT = 200
    /** Booleans, default true: which changes of a followed departure alert. */
    const val NOTIFY_ON_TIME_CHANGE = "notify_on_time_change"
    const val NOTIFY_ON_DELAY_CHANGE = "notify_on_delay_change"
    const val NOTIFY_ON_POSITION_CHANGE = "notify_on_position_change"

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
    /** Boolean, default false: plan the first page of trips with the offline timetables once they are downloaded. */
    const val OFFLINE_TRIP_PLANNER = "offline_trip_planner"
    /** JSON list of [eu.magicsk.transi.data.models.RecentTripSearch], newest first. */
    const val TRIP_RECENT_SEARCHES = "trip_recent_searches"
    /** cacheID of the search whose trips were cached last. */
    const val TRIP_SEARCH_ID = "trip_search_id"

    /** Int, default 0 (no pass): "My pass" length in days, one of [SeasonPass.DURATIONS]. */
    const val PASS_DAYS = "pass_days"
    /**
     * Long, default 0 (unset): the pass's first day as epoch seconds (since 1970) of its Bratislava midnight.
     * iOS keeps seconds since 2001 (Apple's reference date) under the same key, so the values don't carry over.
     */
    const val PASS_VALID_FROM = "pass_valid_from"
    /** Comma-separated zone names, default [PASS_ZONES_DEFAULT]; "100" always comes with "101". */
    const val PASS_ZONES = "pass_zones"
    const val PASS_ZONES_DEFAULT = "100,101"
    /** Boolean, default false. */
    const val PASS_NETWORK_WIDE = "pass_network_wide"
    /** Boolean, default false: bought with a DPB bank card, valid on city lines in zones 100 and 101 only. */
    const val PASS_BANK_CARD = "pass_bank_card"

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** "My pass" from the settings; null without one. */
fun SharedPreferences.seasonPass(): SeasonPass? = SeasonPass.fromStored(
    days = getInt(Prefs.PASS_DAYS, 0),
    validFromEpochSecond = getLong(Prefs.PASS_VALID_FROM, 0L),
    zones = getString(Prefs.PASS_ZONES, null) ?: Prefs.PASS_ZONES_DEFAULT,
    networkWide = getBoolean(Prefs.PASS_NETWORK_WIDE, false),
    bankCard = getBoolean(Prefs.PASS_BANK_CARD, false),
)

/** An Int setting, also when it was stored as a string (like a ListPreference value). */
fun SharedPreferences.intPref(key: String, default: Int): Int = try {
    getInt(key, default)
} catch (_: ClassCastException) {
    getString(key, null)?.toIntOrNull() ?: default
}
