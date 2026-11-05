package com.github.andreyasadchy.xtra.model.gql.chat

import com.github.andreyasadchy.xtra.model.gql.Error
import kotlinx.serialization.Serializable

@Serializable
class ChannelPointContextResponse(
    val errors: List<Error>? = null,
    val data: Data? = null,
) {
    @Serializable
    class Data(val community: Community)

    @Serializable
    class Community(val channel: Channel)

    @Serializable
    class Channel(
        val self: Self? = null,
        val communityPointsSettings: CommunityPointsSettings? = null,
    )

    @Serializable
    class Self(val communityPoints: Points? = null)

    @Serializable
    class Points(
        val balance: Int? = null,
        val availableClaim: Claim? = null,
    )

    @Serializable
    class Claim(val id: String? = null)

    @Serializable
    class CommunityPointsSettings(
        val isEnabled: Boolean? = null,
        val customRewards: List<Reward>? = null,
    )

    @Serializable
    class Reward(
        val id: String? = null,
        val title: String? = null,
        val cost: Int? = null,
        val prompt: String? = null,
        val isEnabled: Boolean? = null,
        val isInStock: Boolean? = null,
        val isPaused: Boolean? = null,
        val isUserInputRequired: Boolean? = null,
        val image: RewardImage? = null,
    )

    @Serializable
    class RewardImage(
        val url1x: String? = null,
        val url2x: String? = null,
        val url4x: String? = null,
    )
}
