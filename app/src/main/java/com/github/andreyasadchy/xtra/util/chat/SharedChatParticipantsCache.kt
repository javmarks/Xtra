package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.model.helix.user.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide, bounded cache for Shared Chat participant profiles used by stream listings.
 * Network work is supplied by the caller so this class does not own credentials or repositories.
 */
class SharedChatParticipantsCache(
    private val cacheTtlMillis: Long = 120_000L,
    private val emptyTtlMillis: Long = 20_000L,
    private val maxEntries: Int = 100,
    maxConcurrentLoads: Int = 2,
) {
    private data class Entry(
        val users: List<User>,
        val expiresAt: Long,
        val lastAccess: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val networkSlots = Semaphore(maxConcurrentLoads.coerceAtLeast(1))

    suspend fun get(
        broadcasterId: String,
        now: Long = System.currentTimeMillis(),
        loader: suspend () -> List<User>,
    ): List<User> {
        entries[broadcasterId]?.takeIf { it.expiresAt > now }?.let {
            entries[broadcasterId] = it.copy(lastAccess = now)
            return it.users
        }

        return locks.getOrPut(broadcasterId) { Mutex() }.withLock {
            val refreshedAt = System.currentTimeMillis()
            entries[broadcasterId]?.takeIf { it.expiresAt > refreshedAt }?.let {
                entries[broadcasterId] = it.copy(lastAccess = refreshedAt)
                return@withLock it.users
            }

            val users = networkSlots.withPermit { loader() }
            val ttl = if (users.isEmpty()) emptyTtlMillis else cacheTtlMillis
            entries[broadcasterId] = Entry(users, refreshedAt + ttl, refreshedAt)
            trimEntries()
            users
        }
    }

    fun clear() {
        entries.clear()
        locks.clear()
    }

    private fun trimEntries() {
        if (entries.size <= maxEntries) return
        val excess = entries.size - maxEntries
        entries.entries
            .sortedBy { it.value.lastAccess }
            .take(excess)
            .forEach { entries.remove(it.key, it.value) }
    }
}
