package eu.magicsk.transi.repository

import android.content.Context
import androidx.core.content.edit
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.data.models.FollowedTrip
import eu.magicsk.transi.data.models.Journey
import eu.magicsk.transi.data.models.SavedTrip
import eu.magicsk.transi.data.models.savedTripGson
import eu.magicsk.transi.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The trip LiveUpdatesService follows, for the screens that show it, and its saved copy, which restores it after the
 * process ends. Use on the main thread.
 */
@Singleton
class FollowedTrips @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = Prefs.get(context)
    private val current = MutableStateFlow<FollowedTrip?>(null)

    /** Null while no trip is followed. */
    val trip: StateFlow<FollowedTrip?> = current.asStateFlow()

    /** The followed journey, else the saved one the app restores when it starts. */
    val journey: Journey? get() = current.value?.journey ?: saved()?.journey

    fun publish(trip: FollowedTrip?) {
        current.value = trip
    }

    /** The saved trip while it still has steps to show; null when there is none, it is over or unreadable. */
    fun saved(now: Instant = Instant.now()): SavedTrip? {
        val json = prefs.getString(Prefs.FOLLOWED_TRIP, null) ?: return null
        val trip = try {
            savedTripGson.fromJson(json, SavedTrip::class.java)
        } catch (_: JsonParseException) {
            null
        }
        return trip?.takeIf { it.journey.parts.isNotEmpty() && !it.isOver(now) }
    }

    fun save(trip: SavedTrip) = prefs.edit { putString(Prefs.FOLLOWED_TRIP, savedTripGson.toJson(trip)) }

    fun clear() = prefs.edit { remove(Prefs.FOLLOWED_TRIP) }
}
