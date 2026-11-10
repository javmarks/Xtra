package com.github.andreyasadchy.xtra.util.chat

import com.github.andreyasadchy.xtra.util.WebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.random.Random

/**
 * Shares one Twitch IRC WebSocket between all active live-chat ViewModels.
 * Each channel keeps its own listeners so messages and room state remain isolated.
 */
class ChatReadWebSocketManager {
    private data class Registration(
        val channelLogin: String,
        val readListener: ChatReadWebSocket.Listener,
        val writeListener: ChatReadWebSocket.Listener?,
        val job: Job = Job(),
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrations = LinkedHashMap<Job, Registration>()
    private var webSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private var connected = false
    private var pingTimer: Timer? = null
    private var pongTimer: Timer? = null
    private var userLogin: String? = null
    private var userToken: String? = null

    fun register(
        channelLogin: String,
        userLogin: String?,
        userToken: String?,
        readListener: ChatReadWebSocket.Listener,
        writeListener: ChatReadWebSocket.Listener?,
    ): Job {
        val registration = Registration(channelLogin, readListener, writeListener)
        var shouldConnect = false
        var shouldJoin = false
        var currentSocket: WebSocket? = null

        synchronized(lock) {
            val channelAlreadyRegistered = registrations.values.any {
                it.channelLogin.equals(channelLogin, ignoreCase = true)
            }
            registrations[registration.job] = registration
            if (webSocket == null || connectionJob?.isActive != true) {
                this.userLogin = userLogin
                this.userToken = userToken
                shouldConnect = true
            } else if (this.userToken != userToken || this.userLogin != userLogin) {
                this.userLogin = userLogin
                this.userToken = userToken
                shouldConnect = true
            } else if (connected && !channelAlreadyRegistered) {
                shouldJoin = true
                currentSocket = webSocket
            }
        }

        if (shouldConnect) {
            restartConnection()
        } else if (shouldJoin) {
            scope.launch { currentSocket?.write("JOIN #$channelLogin") }
        }
        return registration.job
    }

    fun unregister(channelLogin: String, registrationJob: Job?) {
        var socketToPart: WebSocket? = null
        var shouldStop = false
        var shouldPart = false
        synchronized(lock) {
            val registration = registrationJob?.let { registrations.remove(it) }
            if (registration != null) {
                registration.job.cancel()
                shouldStop = registrations.isEmpty()
                shouldPart = !shouldStop && registrations.values.none {
                    it.channelLogin.equals(channelLogin, ignoreCase = true)
                }
                if (connected && shouldPart) socketToPart = webSocket
            }
        }
        if (shouldStop) {
            stopConnection()
        } else if (shouldPart) {
            scope.launch { socketToPart?.write("PART #$channelLogin") }
        }
    }

    private fun restartConnection() {
        stopConnection()
        synchronized(lock) {
            if (registrations.isEmpty()) return
            val socket = WebSocket("wss://irc-ws.chat.twitch.tv", SocketListener())
            webSocket = socket
            connected = false
            socket.coroutineScope = scope
            connectionJob = scope.launch(Dispatchers.IO) { socket.start() }
        }
    }

    private fun stopConnection() {
        val oldSocket: WebSocket?
        val oldJob: Job?
        synchronized(lock) {
            connected = false
            oldSocket = webSocket
            oldJob = connectionJob
            webSocket = null
            connectionJob = null
            pingTimer?.cancel()
            pongTimer?.cancel()
            pingTimer = null
            pongTimer = null
        }
        scope.launch(Dispatchers.IO) {
            runCatching { oldSocket?.disconnect() }
            oldJob?.cancel()
        }
    }

    private inner class SocketListener : WebSocket.Listener {
        override suspend fun onConnect(webSocket: WebSocket) {
            webSocket.write("CAP REQ :twitch.tv/tags twitch.tv/commands")
            val login: String?
            val token: String?
            val channels: List<String>
            synchronized(lock) {
                if (this@ChatReadWebSocketManager.webSocket !== webSocket) return
                login = userLogin
                token = userToken
                channels = registrations.values.map { it.channelLogin }.distinctBy { it.lowercase() }
                connected = true
            }
            if (!token.isNullOrBlank() && !login.isNullOrBlank()) {
                webSocket.write("PASS oauth:$token")
                webSocket.write("NICK $login")
            } else {
                webSocket.write("NICK justinfan${Random.nextInt(1000, 10000)}")
            }
            channels.forEach { webSocket.write("JOIN #$it") }
            val current = synchronized(lock) { registrations.values.toList() }
            current.forEach {
                it.readListener.onConnect()
                it.writeListener?.onConnect()
            }
            pingTimer?.cancel()
            pongTimer?.cancel()
            startPingTimer(webSocket)
        }

        override suspend fun onMessage(webSocket: WebSocket, message: String) {
            message.removeSuffix("\r\n").split("\r\n").forEach { line ->
                when {
                    line.startsWith("PING") -> webSocket.write("PONG")
                    line.startsWith("PONG") -> {
                        pingTimer?.cancel()
                        pongTimer?.cancel()
                        startPingTimer(webSocket)
                    }
                    line.startsWith("RECONNECT") -> {
                        pingTimer?.cancel()
                        pongTimer?.cancel()
                        webSocket.disconnect()
                    }
                    line.isNotBlank() -> route(ChatUtils.parseIRCMessage(line))
                }
            }
        }

        override suspend fun onDisconnect(webSocket: WebSocket, message: String, fullMsg: String?) {
            val current = synchronized(lock) {
                if (this@ChatReadWebSocketManager.webSocket === webSocket) connected = false
                registrations.values.toList()
            }
            // A single shared 429 should not create one duplicate error row per channel.
            if (message.contains("429", ignoreCase = true) || fullMsg?.contains("429", ignoreCase = true) == true) {
                current.firstOrNull()?.readListener?.onDisconnect(message, fullMsg)
            } else {
                current.forEach { it.readListener.onDisconnect(message, fullMsg) }
            }
        }
    }

    private suspend fun route(message: ChatUtils.IRCMessage) {
        val command = message.command ?: return
        val target = message.params.firstOrNull()
            ?.removePrefix("#")
            ?.lowercase()
        val recipients = synchronized(lock) {
            if (target.isNullOrBlank() || target == "*") {
                registrations.values.toList()
            } else {
                registrations.values.filter { it.channelLogin.equals(target, ignoreCase = true) }
            }
        }
        recipients.forEach { registration ->
            when (command) {
                "PRIVMSG" -> registration.readListener.onChatMessage(message, false)
                "USERNOTICE" -> registration.readListener.onChatMessage(message, true)
                "CLEARMSG" -> registration.readListener.onClearMessage(message)
                "CLEARCHAT" -> registration.readListener.onClearChat(message)
                "NOTICE" -> {
                    val listener = registration.writeListener ?: registration.readListener
                    listener.onNotice(message)
                }
                "ROOMSTATE" -> registration.readListener.onRoomState(message)
                "USERSTATE" -> registration.writeListener?.onUserState(message)
            }
        }
    }

    private fun startPingTimer(webSocket: WebSocket) {
        pingTimer = Timer().apply {
            schedule(270000) {
                scope.launch {
                    if (synchronized(lock) { this@ChatReadWebSocketManager.webSocket === webSocket }) {
                        webSocket.write("PING")
                        startPongTimer(webSocket)
                    }
                }
            }
        }
    }

    private fun startPongTimer(webSocket: WebSocket) {
        pongTimer = Timer().apply {
            schedule(10000) {
                scope.launch {
                    if (synchronized(lock) { this@ChatReadWebSocketManager.webSocket === webSocket }) {
                        webSocket.disconnect()
                    }
                }
            }
        }
    }
}
