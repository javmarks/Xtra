package com.github.andreyasadchy.xtra.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class DropsInventorySnapshot(
    val campaigns: List<DropCampaign>,
    val claimedBenefitIds: Set<String>,
)

data class DropCampaign(
    val id: String,
    val name: String,
    val gameName: String?,
    val imageUrl: String?,
    val status: String?,
    val startsAt: String?,
    val endsAt: String?,
    val isAccountConnected: Boolean?,
    val accountLinkUrl: String?,
    val drops: List<DropReward>,
)

data class DropReward(
    val id: String,
    val name: String,
    val startsAt: String?,
    val endsAt: String?,
    val requiredMinutes: Int?,
    val currentMinutes: Int?,
    val instanceId: String?,
    val isClaimed: Boolean?,
    val rewards: List<DropBenefit>,
)

data class DropBenefit(
    val id: String,
    val name: String,
    val distributionType: String?,
    val imageUrl: String?,
)

/**
 * Parses the Twitch Inventory response using the currentUser.inventory shape.
 *
 * Missing optional fields are retained as null. A missing inventory root is treated
 * as an invalid response instead of an empty inventory, so callers don't mistake
 * authentication or GraphQL errors for a user with no Drops.
 */
object DropsInventoryParser {

    fun parse(rawResponse: String): DropsInventorySnapshot? {
        val root = runCatching { Json.parseToJsonElement(rawResponse).jsonObject }.getOrNull()
            ?: return null
        val currentUser = root.objectAt("data")?.objectAt("currentUser") ?: return null
        val inventory = currentUser.objectAt("inventory") ?: return null

        val campaigns = inventory.arrayAt("dropCampaignsInProgress")
            .mapNotNull { it.asObject()?.toCampaign() }

        val claimedBenefitIds = inventory.arrayAt("gameEventDrops")
            .mapNotNull { it.asObject()?.stringAt("id") }
            .toSet()

        return DropsInventorySnapshot(campaigns, claimedBenefitIds)
    }

    fun parseCampaignDetails(rawResponse: String): DropCampaign? {
        val root = runCatching { Json.parseToJsonElement(rawResponse).jsonObject }.getOrNull()
            ?: return null
        val campaign = root.objectAt("data")?.objectAt("user")?.objectAt("dropCampaign")
            ?: return null
        return campaign.toCampaign()
    }

    private fun JsonObject.toCampaign(): DropCampaign? {
        val id = stringAt("id") ?: return null
        val name = stringAt("name") ?: return null
        val game = objectAt("game")

        return DropCampaign(
            id = id,
            name = name,
            gameName = game?.stringAt("name"),
            imageUrl = game?.stringAt("boxArtURL"),
            status = stringAt("status"),
            startsAt = stringAt("startAt"),
            endsAt = stringAt("endAt"),
            isAccountConnected = objectAt("self")?.booleanAt("isAccountConnected"),
            accountLinkUrl = stringAt("accountLinkURL"),
            drops = arrayAt("timeBasedDrops").mapNotNull { it.asObject()?.toDropReward() },
        )
    }

    private fun JsonObject.toDropReward(): DropReward? {
        val id = stringAt("id") ?: return null
        val name = stringAt("name") ?: return null
        val self = objectAt("self")

        return DropReward(
            id = id,
            name = name,
            startsAt = stringAt("startAt"),
            endsAt = stringAt("endAt"),
            requiredMinutes = intAt("requiredMinutesWatched"),
            currentMinutes = self?.intAt("currentMinutesWatched"),
            instanceId = self?.stringAt("dropInstanceID"),
            isClaimed = self?.booleanAt("isClaimed"),
            rewards = arrayAt("benefitEdges").mapNotNull { it.asObject()?.toDropBenefit() },
        )
    }

    private fun JsonObject.toDropBenefit(): DropBenefit? {
        val benefit = objectAt("benefit") ?: return null
        val id = benefit.stringAt("id") ?: return null
        val name = benefit.stringAt("name") ?: return null

        return DropBenefit(
            id = id,
            name = name,
            distributionType = benefit.stringAt("distributionType"),
            imageUrl = benefit.stringAt("imageAssetURL"),
        )
    }

    private fun JsonObject.objectAt(key: String): JsonObject? =
        this[key]?.asObject()

    private fun JsonObject.arrayAt(key: String): JsonArray =
        this[key] as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.stringAt(key: String): String? =
        this[key]?.asString()

    private fun JsonObject.intAt(key: String): Int? =
        this[key]?.asInt()

    private fun JsonObject.booleanAt(key: String): Boolean? =
        this[key]?.asBoolean()

    private fun JsonElement.asObject(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun JsonElement.asString(): String? =
        runCatching { jsonPrimitive.contentOrNull }.getOrNull()

    private fun JsonElement.asInt(): Int? =
        runCatching { jsonPrimitive.intOrNull }.getOrNull()

    private fun JsonElement.asBoolean(): Boolean? =
        runCatching { jsonPrimitive.booleanOrNull }.getOrNull()
}
