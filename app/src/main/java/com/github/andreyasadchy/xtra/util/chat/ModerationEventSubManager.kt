package com.github.andreyasadchy.xtra.util.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Shares one EventSub transport between the active chat ViewModels for the same
 * signed-in account. Each channel keeps its own listener and subscriptions.
 */
class ModerationEventSubManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    // All subscription creation for this transport must be sequential.
    private val subscriptionMutex = Mutex()
    private val listeners = LinkedHashMap<String, MutableList<Pair<String, EventSubWebSocket.Listener>>>()
    private var socket: EventSubWebSocket? = null
    private var socketJob: Job? = null
    private var sessionId: String? = null
    // Keep shutdown and restart serialized. EventSub's close handshake can take
    // several seconds; starting a replacement before it finishes can briefly
    // leave multiple transports alive for the same user/client pair.
    private var disconnecting = false
    private var subscriptionErrorReported = false

    // Twitch limits the total subscription cost on one WebSocket transport to 10.
    // Shared Chat begin costs 1 and channel.moderate costs 2. Reserve room for
    // one moderator subscription so adding more chats can never push an already
    // active moderator subscription over the transport limit.
    private fun subscriptionsWithinCostLimitLocked(): List<Pair<String, EventSubWebSocket.Listener>> {
        var sharedChatCount = 0
        var moderationCount = 0
        return listeners.values.flatten()
            .distinct()
            .filter { (key, _) ->
                when (key) {
                    "shared_chat" -> if (sharedChatCount < 8) {
                        sharedChatCount++
                        true
                    } else false
                    "moderation" -> if (moderationCount < 1) {
                        moderationCount++
                        true
                    } else false
                    else -> false
                }
            }
    }

    /** Allow only one subscription-failure row for the lifetime of a shared socket. */
    fun shouldReportSubscriptionError(): Boolean = synchronized(lock) {
        if (subscriptionErrorReported) false else {
            subscriptionErrorReported = true
            true
        }
    }

    private fun startSocketLocked() {
        if (socket != null || disconnecting || listeners.isEmpty()) return
        subscriptionErrorReported = false
        val sharedSocket = EventSubWebSocket(SharedListener())
        socket = sharedSocket
        socketJob = sharedSocket.connect(scope)
    }

    fun register(channelId: String, listener: EventSubWebSocket.Listener, subscriptionKey: String) {
        synchronized(lock) {
            val channelListeners = listeners.getOrPut(channelId) { mutableListOf() }
            if (channelListeners.any { it.second === listener }) return
            val shouldSubscribe = channelListeners.none { it.first == subscriptionKey }
            channelListeners.add(subscriptionKey to listener)
            val activeSessionId = sessionId
            if (activeSessionId != null && shouldSubscribe &&
                subscriptionsWithinCostLimitLocked().any { it.first == subscriptionKey && it.second === listener }
            ) {
                scope.launch { subscriptionMutex.withLock { listener.onWelcomeMessage(activeSessionId) } }
            } else if (activeSessionId == null) {
                startSocketLocked()
            }
        }
    }

    fun unregister(channelId: String, listener: EventSubWebSocket.Listener) {
        val oldSocket: EventSubWebSocket?
        val oldJob: Job?
        synchronized(lock) {
            val channelListeners = listeners[channelId] ?: return
            channelListeners.removeAll { it.second === listener }
            if (channelListeners.isNotEmpty()) return
            listeners.remove(channelId)
            if (listeners.isNotEmpty()) return
            oldSocket = socket
            oldJob = socketJob
            socket = null
            socketJob = null
            sessionId = null
            disconnecting = true
        }
        scope.launch {
            try {
                oldSocket?.disconnect(oldJob)
            } finally {
                synchronized(lock) {
                    disconnecting = false
                    // A stream may have been reopened while the old socket was
                    // shutting down. Start exactly one replacement only after
                    // the previous transport has completed its close.
                    startSocketLocked()
                }
            }
        }
    }

    private fun listenersFor(event: JSONObject): List<EventSubWebSocket.Listener> {
        val channelId = event.optString("broadcaster_user_id").takeIf { it.isNotBlank() } ?: return emptyList()
        return synchronized(lock) { listeners[channelId]?.map { it.second }.orEmpty() }
    }

    private inner class SharedListener : EventSubWebSocket.Listener {
        override suspend fun onConnect() = Unit

        override suspend fun onWelcomeMessage(sessionId: String) {
            val active = synchronized(lock) {
                this@ModerationEventSubManager.sessionId = sessionId
                subscriptionsWithinCostLimitLocked().map { it.second }.distinct()
            }
            active.forEach { listener ->
                subscriptionMutex.withLock { listener.onWelcomeMessage(sessionId) }
            }
        }

        override suspend fun onChatMessage(event: JSONObject, timestamp: String?) {
            listenersFor(event).forEach { it.onChatMessage(event, timestamp) }
        }

        override suspend fun onUserNotice(event: JSONObject, timestamp: String?) {
            listenersFor(event).forEach { it.onUserNotice(event, timestamp) }
        }

        override suspend fun onClearChat(event: JSONObject, timestamp: String?) {
            listenersFor(event).forEach { it.onClearChat(event, timestamp) }
        }

        override suspend fun onRoomState(event: JSONObject, timestamp: String?) {
            listenersFor(event).forEach { it.onRoomState(event, timestamp) }
        }

        override suspend fun onModerate(event: JSONObject, timestamp: String?) {
            listenersFor(event).forEach { it.onModerate(event, timestamp) }
        }

        override suspend fun onSharedChat(event: JSONObject, subscriptionType: String) {
            listenersFor(event).forEach { it.onSharedChat(event, subscriptionType) }
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            val active = synchronized(lock) {
                this@ModerationEventSubManager.sessionId = null
                listeners.values.flatten().map { it.second }.distinct()
            }
            active.forEach { it.onDisconnect(message, fullMsg) }
        }
    }
}
