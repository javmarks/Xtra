package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class WatchStreakResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(val channel: Channel? = null)

    @Serializable
    class Channel(
        val id: String? = null,
        val self: Self? = null,
    )

    @Serializable
    class Self(val watchStreakMilestone: Milestone? = null)

    @Serializable
    class Milestone(
        val watchStreakMilestone: ViewerMilestone? = null,
        val watchStreakThreshold: Int = 0,
        val watchStreakCopoBonus: Int = 0,
        val state: String = "",
        val expiresAt: String? = null,
    )

    @Serializable
    class ViewerMilestone(
        val id: String? = null,
        val value: String? = null,
        val achievementTimestamp: String? = null,
        val shareStatus: String? = null,
    )
}
