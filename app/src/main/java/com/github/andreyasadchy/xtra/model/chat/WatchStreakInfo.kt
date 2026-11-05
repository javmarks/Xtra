package com.github.andreyasadchy.xtra.model.chat

data class WatchStreakInfo(
    val count: Int,
    val threshold: Int,
    val bonusPoints: Int,
    val state: String,
    val expiresAt: String? = null,
    val milestoneId: String? = null,
    val shareStatus: String = "",
)
