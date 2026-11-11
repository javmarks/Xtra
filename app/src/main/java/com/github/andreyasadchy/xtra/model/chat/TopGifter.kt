package com.github.andreyasadchy.xtra.model.chat

/** A viewer ranked by the number of gift subscriptions observed in the current stream. */
data class TopGifter(
    val userId: String,
    val displayName: String,
    val giftCount: Int,
    val profileImageUrl: String? = null,
)
