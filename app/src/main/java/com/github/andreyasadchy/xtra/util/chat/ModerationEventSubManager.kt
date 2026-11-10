package com.github.andreyasadchy.xtra.util.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Shares one EventSub transport between the active chat ViewModels for the same
 * signed-in account. Each channel keeps its own listener and subscriptions.
 */
class ModerationEventSubManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val listeners = LinkedHashMap<String, EventSubWebSocket.Listener>()
    // Keep subscriptions alive for the lifetime of the shared socket session.
    // Reopening a channel replaces its listener, but must not create duplicate subscriptions.
    private val subscribedChannels = HashSet<String>()
    private var socket: EventSubWebSocket? = null
    private var socketJob: Job? = null
    private var sessionId: String? = null
    // Keep shutdown and restart serialized. EventSub's close handshake can take
    // several seconds; starting a replacement before it finishes can briefly
    // leave multiple transports alive for the same user/client pair.
    private var disconnecting = false
    private var subscriptionErrorReported = false

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

    fun register(channelId: String, listener: EventSubWebSocket.Listener) {
        synchronized(lock) {
            listeners[channelId] = listener
            val activeSessionId = sessionId
            if (activeSessionId != null) {
                if (subscribedChannels.add(channelId)) {
                    scope.launch { listener.onWelcomeMessage(activeSessionId) }
                }
            } else {
                startSocketLocked()
            }
        }
    }

    fun unregister(channelId: String, listener: EventSubWebSocket.Listener) {
        val oldSocket: EventSubWebSocket?
        val oldJob: Job?
        synchronized(lock) {
            if (listeners[channelId] !== listener) return
            listeners.remove(channelId)
            if (listeners.isNotEmpty()) return
            subscribedChannels.clear()
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

    private fun listenerFor(event: JSONObject): EventSubWebSocket.Listener? {
        val channelId = event.optString("broadcaster_user_id").takeIf { it.isNotBlank() } ?: return null
        return synchronized(lock) { listeners[channelId] }
    }

    private inner class SharedListener : EventSubWebSocket.Listener {
        override suspend fun onConnect() = Unit

        override suspend fun onWelcomeMessage(sessionId: String) {
            val active = synchronized(lock) {
                this@ModerationEventSubManager.sessionId = sessionId
                subscribedChannels.clear()
                subscribedChannels.addAll(listeners.keys)
                listeners.values.toList()
            }
            active.forEach { it.onWelcomeMessage(sessionId) }
        }

        override suspend fun onChatMessage(event: JSONObject, timestamp: String?) {
            listenerFor(event)?.onChatMessage(event, timestamp)
        }

        override suspend fun onUserNotice(event: JSONObject, timestamp: String?) {
            listenerFor(event)?.onUserNotice(event, timestamp)
        }

        override suspend fun onClearChat(event: JSONObject, timestamp: String?) {
            listenerFor(event)?.onClearChat(event, timestamp)
        }

        override suspend fun onRoomState(event: JSONObject, timestamp: String?) {
            listenerFor(event)?.onRoomState(event, timestamp)
        }

        override suspend fun onModerate(event: JSONObject, timestamp: String?) {
            listenerFor(event)?.onModerate(event, timestamp)
        }

        override suspend fun onSharedChat(event: JSONObject, subscriptionType: String) {
            listenerFor(event)?.onSharedChat(event, subscriptionType)
        }

        override suspend fun onDisconnect(message: String, fullMsg: String?) {
            val active = synchronized(lock) {
                this@ModerationEventSubManager.sessionId = null
                subscribedChannels.clear()
                listeners.values.toList()
            }
            active.forEach { it.onDisconnect(message, fullMsg) }
        }
    }
}
