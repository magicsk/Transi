package eu.magicsk.transi.repository

import android.content.Context
import androidx.annotation.MainThread
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.magicsk.transi.data.models.TicketCatalogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The IDS BK ticket catalogue, cached in files/ticket_types.json and checked for changes at most once a day. Only
 * [complete][TicketCatalogue.isComplete] catalogues are published or cached; others count as a failed load.
 */
@Singleton
class TicketCatalogueProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: DataRepository,
    private val idsbkSession: IdsbkSession,
) {
    data class State(
        val catalogue: TicketCatalogue? = null,
        /** Nothing cached and the last fetch failed. */
        val loadFailed: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val file = File(context.filesDir, "ticket_types.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** When the catalogue was last confirmed current, in epoch milliseconds. Main thread only, like [job]. */
    private var checkedAt: Long? = null
    private var job: Job? = null

    /** Publishes the cached catalogue, then refreshes it when the cache is a day old. */
    @MainThread
    fun load() {
        if (job?.isActive == true || isFresh(checkedAt)) return
        mutableState.update { it.copy(loadFailed = false) }
        job = scope.launch {
            val (cached, cachedAt) = withContext(Dispatchers.IO) { readCache() }
            if (cached != null) mutableState.update { it.copy(catalogue = cached) }
            // The file's modification time records the last check.
            if (cached != null && isFresh(cachedAt)) {
                checkedAt = cachedAt
                return@launch
            }

            val fetched = idsbkSession.token()
                ?.let { repository.getTicketTypes(it, cached?.cacheKey ?: "null").data }
            val now = System.currentTimeMillis()
            when {
                fetched?.isComplete == true -> {
                    checkedAt = now
                    mutableState.value = State(catalogue = fetched)
                    withContext(Dispatchers.IO) { writeCache(fetched) }
                }
                // Only the cached key came back: the cached copy is current. Anything else keeps it unconfirmed.
                fetched != null && cached?.isConfirmed(fetched) == true -> {
                    checkedAt = now
                    withContext(Dispatchers.IO) { file.setLastModified(now) }
                }
                else -> mutableState.update { it.copy(loadFailed = it.catalogue == null) }
            }
        }
    }

    /** Checked less than a day ago; a check in the future, from a clock that was wrong, is stale. */
    private fun isFresh(checkedAt: Long?) =
        checkedAt != null && System.currentTimeMillis() - checkedAt in 0 until REFRESH_INTERVAL_MS

    /** The cached catalogue and when it was last checked; a null catalogue without a readable, complete cache. */
    private fun readCache(): Pair<TicketCatalogue?, Long?> = try {
        gson.fromJson(file.readText(), TicketCatalogue::class.java)?.takeIf { it.isComplete } to
            file.lastModified().takeIf { it > 0 }
    } catch (_: IOException) {
        null to null
    } catch (_: JsonParseException) {
        null to null
    }

    private fun writeCache(catalogue: TicketCatalogue) {
        try {
            file.writeText(gson.toJson(catalogue))
        } catch (_: IOException) {
            // Kept in memory; the next launch fetches it again.
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
