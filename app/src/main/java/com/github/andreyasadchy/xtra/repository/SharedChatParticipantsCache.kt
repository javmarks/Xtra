package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.model.helix.user.User
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-wide, bounded cache for active Twitch Shared Chat participants.
 *
 * Callers should request data only for a small, visible subset of streams. Network work is
 * serialized per broadcaster and globally limited so stream-list scrolling is never blocked.
 */
class SharedChatParticipantsCache(
    private val helixRepository: HelixRepository,
) {
    private data class Entry(
        val users: List<User>,
        val expiresAt: Long,
    )

    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val generations = ConcurrentHashMap<String, Long>()
    private val requests = Semaphore(2)

    suspend fun getParticipants(
        broadcasterId: String,
        headers: Map<String, String>,
        networkLibrary: String?,
    ): List<User> {
        if (broadcasterId.isBlank()) return emptyList()

        readCache(broadcasterId)?.let { return it }

        val lock = locks.computeIfAbsent(broadcasterId) { Mutex() }
        return lock.withLock {
            readCache(broadcasterId)?.let { return@withLock it }

            requests.withPermit {
                val generation = synchronized(cache) { generations[broadcasterId] ?: 0L }
                val session = helixRepository.getSharedChatSession(
                    networkLibrary,
                    headers,
                    broadcasterId,
                ).data.firstOrNull()

                if (session == null) {
                    writeCacheIfCurrent(broadcasterId, emptyList(), NEGATIVE_TTL_MS, generation)
                    return@withPermit emptyList()
                }

                val participantIds = buildList {
                    session.hostBroadcasterId?.takeIf { it.isNotBlank() }?.let(::add)
                    session.participants.mapNotNullTo(this) {
                        it.broadcasterId?.takeIf(String::isNotBlank)
                    }
                }.distinct()

                if (participantIds.isEmpty()) {
                    writeCacheIfCurrent(broadcasterId, emptyList(), NEGATIVE_TTL_MS, generation)
                    return@withPermit emptyList()
                }

                val users = participantIds.chunked(100).flatMap { ids ->
                    helixRepository.getUsers(networkLibrary, headers, ids = ids).data
                }
                writeCacheIfCurrent(broadcasterId, users, CACHE_TTL_MS, generation)
                users
            }
        }
    }

    /** Force the next stream-list bind to fetch current session participants. */
    fun invalidate(broadcasterId: String) {
        if (broadcasterId.isBlank()) return
        synchronized(cache) {
            generations[broadcasterId] = (generations[broadcasterId] ?: 0L) + 1L
            cache.remove(broadcasterId)
        }
    }

    private fun writeCacheIfCurrent(key: String, users: List<User>, ttlMs: Long, generation: Long) {
        synchronized(cache) {
            if ((generations[key] ?: 0L) == generation) {
                writeCache(key, users, ttlMs)
            }
        }
    }

    private fun readCache(key: String): List<User>? = synchronized(cache) {
        val entry = cache[key] ?: return@synchronized null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            cache.remove(key)
            null
        } else {
            entry.users
        }
    }

    private fun writeCache(key: String, users: List<User>, ttlMs: Long) {
        synchronized(cache) {
            cache[key] = Entry(users, System.currentTimeMillis() + ttlMs)
            while (cache.size > MAX_ENTRIES) {
                val eldest = cache.entries.iterator()
                if (eldest.hasNext()) {
                    eldest.next()
                    eldest.remove()
                } else {
                    break
                }
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 100
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val NEGATIVE_TTL_MS = 45 * 1000L
    }
}
