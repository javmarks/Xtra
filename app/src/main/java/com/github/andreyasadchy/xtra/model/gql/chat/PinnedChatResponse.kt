package com.github.andreyasadchy.xtra.model.gql.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class PinnedChatResponse(
    val data: Data? = null,
) {
    @Serializable
    class Data(
        val channel: Channel? = null,
    )

    @Serializable
    class Channel(
        @SerialName("pinnedChatMessages")
        val pinnedChatMessages: PinnedChatMessages? = null,
    )

    @Serializable
    class PinnedChatMessages(
        val edges: List<Edge> = emptyList(),
    )

    @Serializable
    class Edge(
        val node: Node? = null,
    )

    @Serializable
    class Node(
        val pinnedMessage: PinnedMessage? = null,
        val pinnedBy: User? = null,
        val startsAt: String? = null,
        val endsAt: String? = null,
    )

    @Serializable
    class PinnedMessage(
        val id: String? = null,
        val content: Content? = null,
        val sender: User? = null,
    )

    @Serializable
    class Content(
        val text: String? = null,
    )

    @Serializable
    class User(
        val id: String? = null,
        val displayName: String? = null,
    )
}
