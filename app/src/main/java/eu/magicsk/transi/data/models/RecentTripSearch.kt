package eu.magicsk.transi.data.models

import com.google.gson.annotations.SerializedName
import eu.magicsk.transi.data.remote.responses.Stop
import java.time.Duration
import java.time.Instant

const val RECENT_SEARCH_LIMIT = 10
/** Journeys kept per recent search, to keep the backed-up prefs file small. */
const val CACHED_JOURNEY_LIMIT = 30

/** Stored as "arrival"/"departure" in the recents JSON. */
enum class ArrivalDeparture {
    @SerializedName("arrival")
    ARRIVAL,

    @SerializedName("departure")
    DEPARTURE,
}

data class RecentTripSearch(
    val from: Stop,
    val to: Stop,
    val arrivalDeparture: ArrivalDeparture,
    val arrivalDepartureDate: Instant,
    val arrivalDepartureCustomDate: Boolean,
    /** The journeys found, kept for [Prefs.TRIP_SAVE_DURATION][eu.magicsk.transi.util.Prefs.TRIP_SAVE_DURATION]. */
    val trip: List<Journey>? = null,
    val tripSavedAt: Instant? = null,
) {
    val id get() = searchId(from, to)

    /** Tells the search whose journeys were cached last from another search between the same stops. */
    val cacheID get() = "$id:$arrivalDeparture:${if (arrivalDepartureCustomDate) arrivalDepartureDate else "now"}"
}

fun searchId(from: Stop?, to: Stop?) = "${from?.id}:${to?.id}"

/** [search] first, without an older search between the same stops, at most [RECENT_SEARCH_LIMIT]. */
fun List<RecentTripSearch>.remember(search: RecentTripSearch) =
    (listOf(search) + filter { it.id != search.id }).take(RECENT_SEARCH_LIMIT)

/** Whether trips saved at [savedAt] may be shown after a restart: -1 hours keeps them forever, 0 never. */
fun canRestoreTrip(savedAt: Instant?, saveDurationHours: Int, now: Instant) =
    saveDurationHours == -1 ||
        (saveDurationHours > 0 && savedAt != null &&
            Duration.between(savedAt, now) < Duration.ofHours(saveDurationHours.toLong()))

/** The searches as restored on launch: trips saved longer ago than [saveDurationHours] are dropped. */
fun List<RecentTripSearch>.restorable(saveDurationHours: Int, now: Instant) =
    map { if (canRestoreTrip(it.tripSavedAt, saveDurationHours, now)) it else it.copy(trip = null) }

fun encodeRecentSearches(searches: List<RecentTripSearch>): String = plannerGson.toJson(searches.toTypedArray())

/** The stored searches, or none when [json] is missing or unreadable. */
fun decodeRecentSearches(json: String?): List<RecentTripSearch> = try {
    plannerGson.fromJson(json, Array<RecentTripSearch>::class.java)?.take(RECENT_SEARCH_LIMIT).orEmpty()
} catch (_: RuntimeException) {
    // ponytail: corrupt or foreign data starts the recents afresh rather than crashing the planner.
    emptyList()
}
