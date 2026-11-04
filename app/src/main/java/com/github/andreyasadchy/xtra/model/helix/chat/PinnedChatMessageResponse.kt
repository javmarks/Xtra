package com.github.andreyasadchy.xtra.model.helix.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PinnedChatMessageResponse(
    val data: List<PinnedMessage> = emptyList(),
) {
    @Serializable
    class PinnedMessage(
        @SerialName("message_id")
        val messageId: String? = null,
        @SerialName("broadcaster_id")
        val broadcasterId: String? = null,
        @SerialName("broadcaster_name")
        val broadcasterName: String? = null,
        @SerialName("sender_id")
        val senderId: String? = null,
        @SerialName("sender_name")
        val senderName: String? = null,
        @SerialName("sender_login")
        val senderLogin: String? = null,
        @SerialName("pinned_by_user_id")
        val pinnedById: String? = null,
        @SerialName("pinned_by_user_name")
        val pinnedByName: String? = null,
        @SerialName("pinned_by_user_login")
        val pinnedByLogin: String? = null,
        val message: Message? = null,
        @SerialName("pinned_at")
        val pinnedAt: String? = null,
        @SerialName("expires_at")
        val expiresAt: String? = null,
    )

    @Serializable
    class Message(
        val text: String? = null,
    )
}
