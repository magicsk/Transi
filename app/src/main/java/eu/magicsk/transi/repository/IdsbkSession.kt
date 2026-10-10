package eu.magicsk.transi.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The IDS BK session token shared by the table, the trip planner and the timetables (iOS getSessionToken): fetched on
 * first use, kept for the app run, and fetched again by the next caller after a failed startup request.
 */
@Singleton
class IdsbkSession @Inject constructor(private val repository: DataRepository) {
    private val mutex = Mutex()
    @Volatile
    private var cached: String? = null
    /** Finished startup requests; set after [cached]. */
    @Volatile
    private var attempts = 0

    /** Null when the request failed. Callers that arrive while it runs share it and its result. */
    suspend fun token(): String? {
        val attempt = attempts
        cached?.let { return it }
        return mutex.withLock {
            when {
                cached != null -> cached
                attempt != attempts -> null
                else -> repository.getIdsbkSession().data?.session?.takeIf { it.isNotEmpty() }.also {
                    cached = it
                    attempts++
                }
            }
        }
    }
}
