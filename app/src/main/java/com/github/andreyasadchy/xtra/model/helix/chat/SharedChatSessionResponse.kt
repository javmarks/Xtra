package com.github.andreyasadchy.xtra.model.helix.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class SharedChatSessionResponse(
    val data: List<Session> = emptyList(),
) {
    @Serializable
    class Session(
        @SerialName("session_id")
        val sessionId: String? = null,
        @SerialName("host_broadcaster_id")
        val hostBroadcasterId: String? = null,
        val participants: List<Participant> = emptyList(),
    )

    @Serializable
    class Participant(
        @SerialName("broadcaster_id")
        val broadcasterId: String? = null,
    )
}
