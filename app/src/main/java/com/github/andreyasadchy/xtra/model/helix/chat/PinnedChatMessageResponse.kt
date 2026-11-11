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
        @SerialName("sender_user_id")
        val senderId: String? = null,
        @SerialName("sender_user_name")
        val senderName: String? = null,
        @SerialName("sender_user_login")
        val senderLogin: String? = null,
        @SerialName("pinned_by_user_id")
        val pinnedById: String? = null,
        @SerialName("pinned_by_user_name")
        val pinnedByName: String? = null,
        @SerialName("pinned_by_user_login")
        val pinnedByLogin: String? = null,
        val message: Message? = null,
        @SerialName("starts_at")
        val pinnedAt: String? = null,
        @SerialName("ends_at")
        val expiresAt: String? = null,
        @SerialName("updated_at")
        val updatedAt: String? = null,
    )

    @Serializable
    class Message(
        val text: String? = null,
        val fragments: List<Fragment> = emptyList(),
    )

    @Serializable
    class Fragment(
        val type: String? = null,
        val text: String? = null,
        val cheermote: Cheermote? = null,
        val emote: Emote? = null,
        val mention: Mention? = null,
    )

    @Serializable
    class Cheermote(
        val prefix: String? = null,
        val bits: Int? = null,
        val tier: Int? = null,
    )

    @Serializable
    class Emote(
        val id: String? = null,
        @SerialName("emote_set_id")
        val emoteSetId: String? = null,
        @SerialName("owner_id")
        val ownerId: String? = null,
        val format: List<String> = emptyList(),
    )

    @Serializable
    class Mention(
        @SerialName("user_id")
        val userId: String? = null,
        @SerialName("user_login")
        val userLogin: String? = null,
        @SerialName("user_name")
        val userName: String? = null,
    )
}
