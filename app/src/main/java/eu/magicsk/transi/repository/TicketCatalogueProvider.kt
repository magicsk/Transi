package eu.magicsk.transi.repository

import android.content.Context
import androidx.annotation.MainThread
import com.google.gson.Gson
import com.google.gson.JsonParseException
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import eu.magicsk.transi.data.models.TicketCatalogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** The IDS BK ticket catalogue, cached in files/ticket_types.json and checked for changes at most once a day. */
@Singleton
class TicketCatalogueProvider @Inject constructor(
    @ApplicationContext context: Context,
    private val repository: DataRepository,
    private val idsbkSession: IdsbkSession,
) {
    data class State(
        val catalogue: TicketCatalogue? = null,
        val isLoading: Boolean = false,
        /** Nothing cached and the last fetch failed. */
        val loadFailed: Boolean = false,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val file = File(context.filesDir, "ticket_types.json")
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** When the catalogue was last confirmed current, in epoch milliseconds. Main thread only. */
    private var checkedAt: Long? = null

    /** Publishes the cached catalogue, then refreshes it when the cache is a day old. */
    @MainThread
    fun load() {
        if (state.value.isLoading) return
        checkedAt?.let { if (System.currentTimeMillis() - it < REFRESH_INTERVAL_MS) return }
        mutableState.update { it.copy(isLoading = true, loadFailed = false) }
        scope.launch {
            val (cached, cachedAt) = withContext(Dispatchers.IO) { readCache() }
            // The file's modification time records the last check.
            val isFresh = cachedAt != null && System.currentTimeMillis() - cachedAt < REFRESH_INTERVAL_MS
            if (cached != null) mutableState.update { it.copy(catalogue = cached) }
            if (cached != null && isFresh) {
                checkedAt = cachedAt
                mutableState.update { it.copy(isLoading = false) }
                return@launch
            }

            val fetched = idsbkSession.token()
                ?.let { repository.getTicketTypes(it, cached?.cacheKey ?: "null").data }
            val now = System.currentTimeMillis()
            when {
                fetched?.tickets != null && fetched.zones != null -> {
                    checkedAt = now
                    mutableState.value = State(catalogue = fetched)
                    withContext(Dispatchers.IO) { writeCache(fetched) }
                }
                // Only the cached key came back: the cached copy is current. Anything else keeps it unconfirmed.
                fetched != null && cached?.isConfirmed(fetched) == true -> {
                    checkedAt = now
                    mutableState.update { it.copy(isLoading = false) }
                    withContext(Dispatchers.IO) { file.setLastModified(now) }
                }
                else -> mutableState.update { it.copy(isLoading = false, loadFailed = it.catalogue == null) }
            }
        }
    }

    /** The cached catalogue and when it was last checked; nulls without a readable cache. */
    private fun readCache(): Pair<TicketCatalogue?, Long?> = try {
        gson.fromJson(file.readText(), TicketCatalogue::class.java) to file.lastModified().takeIf { it > 0 }
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

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Accessor {
        fun ticketCatalogueProvider(): TicketCatalogueProvider
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L

        /** The singleton, for classes Hilt doesn't inject (the settings screens). */
        fun from(context: Context): TicketCatalogueProvider =
            EntryPointAccessors.fromApplication(context, Accessor::class.java).ticketCatalogueProvider()
    }
}
