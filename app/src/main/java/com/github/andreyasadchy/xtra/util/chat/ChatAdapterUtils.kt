package com.github.andreyasadchy.xtra.util.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.Patterns
import android.view.View
import org.json.JSONObject
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.CheerEmote
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.model.chat.NamePaint
import com.github.andreyasadchy.xtra.model.chat.STVBadge
import com.github.andreyasadchy.xtra.model.chat.STVUser
import com.github.andreyasadchy.xtra.model.chat.TwitchBadge
import com.github.andreyasadchy.xtra.model.chat.TwitchEmote
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintImageSpan
import com.github.andreyasadchy.xtra.ui.view.NamePaintSpan
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import java.text.NumberFormat
import java.util.Random
import kotlin.math.floor
import kotlin.math.pow

object ChatAdapterUtils {

    private val twitchColors = intArrayOf(-65536, -16776961, -16744448, -5103070, -32944, -6632142, -47872, -13726889, -2448096, -2987746, -10510688, -14774017, -38476, -7722014, -16711809)
    private const val RED_HUE_DEGREES = 0f
    private const val GREEN_HUE_DEGREES = 120f
    private const val BLUE_HUE_DEGREES = 240f
    private const val PI_DEGREES = 180f
    private const val TWO_PI_DEGREES = 360f

    fun prepareChatMessage(chatMessage: ChatMessage, context: Context, itemView: View, enableTimestamps: Boolean, timestampFormat: String?, firstMsgVisibility: Int, firstChatMsg: String, redeemedChatMsg: String, redeemedNoMsg: String, rewardChatMsg: String, replyMessage: String, imageClick: ((String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit)?, useRandomColors: Boolean, random: Random, useReadableColors: Boolean, isLightTheme: Boolean, nameDisplay: String?, useBoldNames: Boolean, showNamePaints: Boolean, namePaints: List<NamePaint>, showSTVBadges: Boolean, stvBadges: List<STVBadge>, showGifMessages: Boolean, showPersonalEmotes: Boolean, personalEmoteSets: Map<String, List<Emote>>, stvUsers: List<STVUser>, enableOverlayEmotes: Boolean, showSystemMessageEmotes: Boolean, loggedInUser: String?, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?, userColors: HashMap<String, Int>, savedColors: HashMap<String, Int>, translateAllMessages: Boolean, translateMessage: (ChatMessage, String?) -> Unit, showLanguageDownloadDialog: (ChatMessage, String) -> Unit, hideErrors: Boolean, localTwitchEmotes: List<TwitchEmote>, thirdPartyEmotes: List<Emote>, globalBadges: List<TwitchBadge>, channelBadges: List<TwitchBadge>, cheerEmotes: List<CheerEmote>, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, savedLocalBadges: MutableMap<String, ByteArray>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, savedLocalEmotes: MutableMap<String, ByteArray>, showSourceChannel: Boolean = false): MessageResult {
        val builder = SpannableStringBuilder()
        val images = ArrayList<Image>()
        var imagePaint: NamePaint? = null
        var userName: String? = null
        var userNameStartIndex: Int? = null
        var wasMentioned = false
        var translated = false
        var builderIndex = 0
        // Frosty/Twitch Shared Chat-style source marker: a compact channel avatar
        // before the message, separate from the chatter's own badges.
        if (showSourceChannel && chatMessage.type != ChatMessage.REPLY_MESSAGE) {
            val sourceImage = chatMessage.sourceChannelImageUrl?.takeIf { it.isNotBlank() }
            val sourceLabel = chatMessage.sourceChannelName?.takeIf { it.isNotBlank() }?.let { "#${it.take(16)}" }
            if (sourceImage != null) {
                builder.append(". ")
                builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                images.add(Image(
                    url1x = sourceImage,
                    url2x = sourceImage,
                    url3x = sourceImage,
                    url4x = sourceImage,
                    size = Image.IMAGE_SIZE_BADGE,
                    fallbackText = sourceLabel,
                    start = builderIndex,
                    end = builderIndex + 1
                ))
                builderIndex += 2
            } else if (sourceLabel != null) {
                builder.append(sourceLabel).append(" ")
                builder.setSpan(
                    ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)),
                    builderIndex,
                    builderIndex + sourceLabel.length,
                    SPAN_EXCLUSIVE_EXCLUSIVE
                )
                builderIndex += sourceLabel.length + 1
            }
        }
        when {
            chatMessage.type == ChatMessage.REPLY_MESSAGE -> {
                val userName = if (chatMessage.reply?.userName != null && chatMessage.reply.userLogin != null && !chatMessage.reply.userLogin.equals(chatMessage.reply.userName, true)) {
                    when (nameDisplay) {
                        "0" -> "${chatMessage.reply.userName}(${chatMessage.reply.userLogin})"
                        "1" -> chatMessage.reply.userName
                        else -> chatMessage.reply.userLogin
                    }
                } else {
                    chatMessage.reply?.userName ?: chatMessage.reply?.userLogin
                }
                val string = replyMessage.format(userName, "")
                builder.append(string)
                builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), 0, string.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                builderIndex += string.length
                val message = chatMessage.reply?.message
                if (message != null) {
                    builder.append(message)
                    builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + message.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    prepareEmotes(chatMessage, message, builder, builderIndex, images, null, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                    builderIndex = builder.length
                }
                itemView.setBackgroundResource(0)
            }
            chatMessage.message.isNullOrBlank() && (chatMessage.systemMsg != null || chatMessage.reward?.title != null) -> {
                if (chatMessage.timestamp != null && enableTimestamps) {
                    val timestamp = TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat)
                    if (timestamp != null) {
                        builder.append("$timestamp ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), 0, timestamp.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex += timestamp.length + 1
                    }
                }
                val noticeLabel = getSubscriptionNoticeLabel(chatMessage.msgId)
                if (noticeLabel != null) {
                    builder.append("$noticeLabel\n")
                    builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + noticeLabel.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(ForegroundColorSpan(getSavedColor("#A970FF", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + noticeLabel.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    builderIndex += noticeLabel.length + 1
                }
                if (chatMessage.systemMsg != null) {
                    builder.append(chatMessage.systemMsg)
                    builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + chatMessage.systemMsg.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (showSystemMessageEmotes) {
                        prepareEmotes(chatMessage, chatMessage.systemMsg, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                    }
                    builderIndex = builder.length
                    val subscriptionDetails = getSubscriptionNoticeDetails(chatMessage.msgId, chatMessage.fullMsg)
                    if (!subscriptionDetails.isNullOrBlank()) {
                        val detailsText = "\n$subscriptionDetails"
                        builder.append(detailsText)
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#B8A0D8", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + detailsText.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex = builder.length
                    }
                    if (chatMessage.translatedMessage != null) {
                        translated = true
                        val result = addTranslation(chatMessage, builder, builderIndex, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                        builderIndex = result
                    } else {
                        if (translateAllMessages) {
                            translateMessage(chatMessage, null)
                        }
                    }
                } else {
                    if (chatMessage.reward?.title != null) {
                        val userName = if (chatMessage.userLogin != null && !chatMessage.userLogin.equals(chatMessage.userName, true)) {
                            when (nameDisplay) {
                                "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
                                "1" -> chatMessage.userName
                                else -> chatMessage.userLogin
                            }
                        } else {
                            chatMessage.userName
                        }
                        val string = redeemedNoMsg.format(userName, chatMessage.reward.title)
                        builder.append("$string ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + string.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (showSystemMessageEmotes) {
                            prepareEmotes(chatMessage, string, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, null, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                        }
                        builderIndex = builder.length
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        images.add(Image(
                            url1x = chatMessage.reward.url1x,
                            url2x = chatMessage.reward.url2x,
                            url3x = chatMessage.reward.url4x,
                            url4x = chatMessage.reward.url4x,
                            size = Image.IMAGE_SIZE_BADGE,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                        if (chatMessage.reward.cost != null) {
                            val cost = NumberFormat.getInstance().format(chatMessage.reward.cost)
                            builder.append(cost)
                            builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + cost.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                            builderIndex += cost.length
                        }
                    }
                }
                // EventSub chat notifications (including subscriptions and gifted subs) should
                // remain visually identifiable even when Twitch supplies no message text.
                itemView.setBackgroundResource(if (!chatMessage.msgId.isNullOrBlank()) R.color.chatMessageNotice else 0)
            }
            else -> {
                if (chatMessage.systemMsg != null) {
                    builder.append("${chatMessage.systemMsg}\n")
                    builderIndex += chatMessage.systemMsg.length + 1
                } else {
                    if (chatMessage.msgId != null) {
                        val msgId = TwitchApiHelper.getMessageIdString(context, chatMessage.msgId) ?: chatMessage.msgId
                        builder.append("$msgId\n")
                        builderIndex += msgId.length + 1
                    }
                }
                if (chatMessage.isFirst && firstMsgVisibility == 0) {
                    builder.append("$firstChatMsg\n")
                    builderIndex += firstChatMsg.length + 1
                }
                if (chatMessage.reward?.title != null) {
                    val string = redeemedChatMsg.format(chatMessage.reward.title)
                    builder.append("$string ")
                    builderIndex += string.length + 1
                    builder.append(". ")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    images.add(Image(
                        url1x = chatMessage.reward.url1x,
                        url2x = chatMessage.reward.url2x,
                        url3x = chatMessage.reward.url4x,
                        url4x = chatMessage.reward.url4x,
                        size = Image.IMAGE_SIZE_BADGE,
                        start = builderIndex++,
                        end = builderIndex++
                    ))
                    if (chatMessage.reward.cost != null) {
                        val cost = NumberFormat.getInstance().format(chatMessage.reward.cost)
                        builder.append(cost)
                        builderIndex += cost.length
                    }
                    builder.append("\n")
                    builderIndex += 1
                } else {
                    if (chatMessage.reward?.id != null && firstMsgVisibility == 0) {
                        builder.append("$rewardChatMsg\n")
                        builderIndex += rewardChatMsg.length + 1
                    }
                }
                if (chatMessage.timestamp != null && enableTimestamps) {
                    val timestamp = TwitchApiHelper.getTimestamp(chatMessage.timestamp, timestampFormat)
                    if (timestamp != null) {
                        builder.append("$timestamp ")
                        builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + timestamp.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        builderIndex += timestamp.length + 1
                    }
                }
                chatMessage.badges?.forEach { chatBadge ->
                    val badge = synchronized(channelBadges) {
                        channelBadges.find { it.setId == chatBadge.setId && it.version == chatBadge.version }
                    } ?:
                    synchronized(globalBadges) {
                        globalBadges.find { it.setId == chatBadge.setId && it.version == chatBadge.version }
                    }
                    if (badge != null) {
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x, badge.title, null, null, null, null, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        images.add(Image(
                            localData = badge.localData?.let { getLocalEmoteData(badge.setId + badge.version, it, savedLocalBadges, chatUrl, getEmoteBytes) },
                            url1x = badge.url1x,
                            url2x = badge.url2x,
                            url3x = badge.url3x,
                            url4x = badge.url4x,
                            size = Image.IMAGE_SIZE_BADGE,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                    }
                }
                val stvUser = if ((showSTVBadges || showNamePaints || showPersonalEmotes) && !chatMessage.userId.isNullOrBlank()) {
                    synchronized(stvUsers) {
                        stvUsers.find { it.userId == chatMessage.userId }
                    }
                } else null
                if (showSTVBadges && !chatMessage.userId.isNullOrBlank()) {
                    val badge = stvUser?.badgeId?.let { badgeId ->
                        synchronized(stvBadges) {
                            stvBadges.find { it.id == badgeId }
                        }
                    }
                    if (badge != null) {
                        builder.append(". ")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(badge.url4x ?: badge.url3x ?: badge.url2x ?: badge.url1x, badge.name, badge.format, true, null, true, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        images.add(Image(
                            url1x = badge.url1x,
                            url2x = badge.url2x,
                            url3x = badge.url3x,
                            url4x = badge.url4x,
                            format = badge.format,
                            size = Image.IMAGE_SIZE_BADGE,
                            isAnimated = true,
                            thirdParty = true,
                            start = builderIndex++,
                            end = builderIndex++
                        ))
                    }
                }
                val color = if (chatMessage.color != null) {
                    getSavedColor(chatMessage.color, savedColors, useReadableColors, isLightTheme)
                } else {
                    userColors[chatMessage.userName] ?: if (useRandomColors) {
                        twitchColors[random.nextInt(twitchColors.size)]
                    } else {
                        -10066329
                    }.let { newColor ->
                        if (useReadableColors) {
                            adaptUsernameColor(newColor, isLightTheme)
                        } else {
                            newColor
                        }.also { if (chatMessage.userName != null) userColors[chatMessage.userName] = it }
                    }
                }
                if (!chatMessage.userName.isNullOrBlank()) {
                    userName = if (chatMessage.userLogin != null && !chatMessage.userLogin.equals(chatMessage.userName, true)) {
                        when (nameDisplay) {
                            "0" -> "${chatMessage.userName}(${chatMessage.userLogin})"
                            "1" -> chatMessage.userName
                            else -> chatMessage.userLogin
                        }
                    } else {
                        chatMessage.userName
                    }
                    builder.append(userName)
                    builder.setSpan(ForegroundColorSpan(color), builderIndex, builderIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (useBoldNames) {
                        builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + userName.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (showNamePaints && !chatMessage.userId.isNullOrBlank()) {
                        stvUser?.paintId?.let { paintId ->
                            synchronized(namePaints) {
                                namePaints.find { it.id == paintId }
                            }
                        }?.let { paint ->
                            when (paint.type) {
                                "LINEAR_GRADIENT", "RADIAL_GRADIENT" -> {
                                    if (paint.colors != null && paint.colorPositions != null) {
                                        builder.setSpan(
                                            NamePaintSpan(
                                                userName,
                                                paint.type,
                                                paint.colors,
                                                paint.colorPositions,
                                                paint.angle,
                                                paint.repeat,
                                                paint.shadows
                                            ),
                                            builderIndex,
                                            builderIndex + userName.length,
                                            SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    }
                                }
                                "URL" -> {
                                    if (!paint.imageUrl.isNullOrBlank()) {
                                        imagePaint = paint
                                        userNameStartIndex = builderIndex
                                    }
                                }
                            }
                        }
                    }
                    builderIndex += userName.length
                    if (!chatMessage.isAction) {
                        builder.append(": ")
                        builderIndex += 2
                    } else {
                        builder.append(" ")
                        builderIndex += 1
                    }
                }
                if (showGifMessages && !chatMessage.gif.isNullOrBlank()) {
                    builder.append("\n")
                    builderIndex += 1
                    builder.append(".")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (imageClick != null) {
                        builder.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                imageClick(chatMessage.gif, chatMessage.message?.removeSurrounding("[", "]"), "gif", true, null, false, null)
                            }

                            override fun updateDrawState(ds: TextPaint) {}
                        }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    images.add(Image(
                        url1x = chatMessage.gif,
                        url2x = chatMessage.gif,
                        url3x = chatMessage.gif,
                        url4x = chatMessage.gif,
                        format = "gif",
                        isAnimated = true,
                        size = Image.IMAGE_SIZE_GIF,
                        start = builderIndex,
                        end = builderIndex + 1
                    ))
                    builderIndex += 1
                } else {
                    if (chatMessage.message != null) {
                        builder.append(chatMessage.message)
                        if (chatMessage.isAction) {
                            builder.setSpan(ForegroundColorSpan(color), builderIndex, builderIndex + chatMessage.message.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        val result = prepareEmotes(chatMessage, chatMessage.message, builder, builderIndex, images, imageClick, useReadableColors, isLightTheme, enableOverlayEmotes, useBoldNames, loggedInUser, chatUrl, getEmoteBytes, savedColors, localTwitchEmotes, showPersonalEmotes, personalEmoteSets, stvUser, thirdPartyEmotes, cheerEmotes, savedLocalTwitchEmotes, savedLocalCheerEmotes, savedLocalEmotes)
                        wasMentioned = result
                        builderIndex = builder.length
                    }
                }
                if (chatMessage.translatedMessage != null) {
                    translated = true
                    val result = addTranslation(chatMessage, builder, builderIndex, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                    builderIndex = result
                } else {
                    if (translateAllMessages) {
                        translateMessage(chatMessage, null)
                    }
                }
                when {
                    chatMessage.isFirst && firstMsgVisibility < 2 -> itemView.setBackgroundResource(R.color.chatMessageFirst)
                    chatMessage.reward?.id != null && firstMsgVisibility < 2 -> itemView.setBackgroundResource(R.color.chatMessageReward)
                    chatMessage.systemMsg != null || chatMessage.msgId != null -> itemView.setBackgroundResource(R.color.chatMessageNotice)
                    wasMentioned -> itemView.setBackgroundResource(R.color.chatMessageMention)
                    else -> itemView.setBackgroundResource(0)
                }
            }
        }
        return MessageResult(builder, images, imagePaint, userName, userNameStartIndex, translated)
    }

    private fun getSubscriptionNoticeDetails(noticeType: String?, fullMsg: String?): String? {
        val rawType = noticeType ?: return null
        val type = rawType.removePrefix("shared_chat_")
        val json = try { fullMsg?.let { JSONObject(it) } } catch (_: Exception) { null } ?: return null
        val event = json.optJSONObject(rawType) ?: json.optJSONObject(type)
        val details = mutableListOf<String>()
        val tierValue = event?.optString("sub_tier")?.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: event?.optString("sub_plan")?.takeIf { !it.isNullOrBlank() && it != "null" }
            ?: json.optString("sub_tier").takeIf { it.isNotBlank() && it != "null" }
            ?: json.optString("sub_plan").takeIf { it.isNotBlank() && it != "null" }
        val tier = when (tierValue) {
            "1000" -> "Tier 1"
            "2000" -> "Tier 2"
            "3000" -> "Tier 3"
            else -> null
        }
        if (tier != null && type in setOf("sub", "resub", "sub_gift", "community_sub_gift", "prime_paid_upgrade")) {
            details.add(tier)
        }
        when (type) {
            "sub" -> {
                val duration = event?.optInt("duration_months", 0) ?: 0
                if (duration > 0) details.add("$duration months")
                if (event?.optBoolean("is_prime") == true) details.add("Prime")
            }
            "resub" -> {
                val months = event?.optInt("cumulative_months", 0) ?: 0
                val streak = event?.optInt("streak_months", 0) ?: 0
                if (months > 0) details.add("$months months total")
                if (streak > 0) details.add("$streak-month streak")
                if (event?.optBoolean("is_prime") == true) details.add("Prime")
                if (event?.optBoolean("is_gift") == true) {
                    val gifter = event?.optString("gifter_user_name")?.takeIf { it.isNotBlank() && it != "null" }
                    details.add(if (gifter != null) "Gifted by $gifter" else "Gift subscription")
                }
            }
            "sub_gift" -> {
                val recipient = event?.optString("recipient_user_name")?.takeIf { !it.isNullOrBlank() && it != "null" }
                val duration = event?.optInt("duration_months", 0) ?: 0
                val cumulative = event?.optInt("cumulative_total", 0) ?: 0
                if (recipient != null) details.add("to $recipient")
                if (duration > 0) details.add("$duration months")
                if (cumulative > 0) details.add("$cumulative gifts given")
            }
            "community_sub_gift" -> {
                val total = event?.optInt("total", 0) ?: 0
                val cumulative = event?.optInt("cumulative_total", 0) ?: 0
                if (total > 0) details.add("$total gifted")
                if (cumulative > 0) details.add("$cumulative gifts given")
            }
            "prime_paid_upgrade" -> details.add("Prime upgrade")
            "gift_paid_upgrade" -> {
                val gifter = event?.optString("gifter_user_name")?.takeIf { !it.isNullOrBlank() && it != "null" }
                if (gifter != null) details.add("Gifted by $gifter")
            }
        }
        return details.distinct().takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun getSubscriptionNoticeLabel(noticeType: String?): String? {
        val type = noticeType?.removePrefix("shared_chat_") ?: return null
        return when (type) {
            "sub" -> "NEW SUBSCRIBER"
            "resub" -> "RESUBSCRIPTION"
            "sub_gift" -> "GIFTED SUB"
            "community_sub_gift" -> "GIFT BOMB"
            "gift_paid_upgrade" -> "GIFT SUB UPGRADE"
            "prime_paid_upgrade" -> "PRIME UPGRADE"
            "pay_it_forward" -> "GIFT FORWARD"
            else -> null
        }
    }

    class MessageResult(
        val builder: SpannableStringBuilder,
        val images: ArrayList<Image>,
        val imagePaint: NamePaint?,
        val userName: String?,
        val userNameStartIndex: Int?,
        val translated: Boolean,
    )

    fun addTranslation(chatMessage: ChatMessage, builder: SpannableStringBuilder, startIndex: Int, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean, showLanguageDownloadDialog: (ChatMessage, String) -> Unit, hideErrors: Boolean): Int {
        var builderIndex = startIndex
        if (!hideErrors || !chatMessage.translationFailed) {
            val translatedMessage = "\n${chatMessage.translatedMessage}"
            builder.append(translatedMessage)
            builder.setSpan(ForegroundColorSpan(getSavedColor("#999999", savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + translatedMessage.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            val messageLanguage = chatMessage.messageLanguage
            if (messageLanguage != null) {
                builder.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        showLanguageDownloadDialog(chatMessage, messageLanguage)
                    }

                    override fun updateDrawState(ds: TextPaint) {}
                }, builderIndex, builderIndex + translatedMessage.length, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            builderIndex += translatedMessage.length
        }
        return builderIndex
    }

    private fun getSavedColor(color: String, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean): Int {
        return savedColors[color] ?: Color.parseColor(color).let { newColor ->
            if (useReadableColors) {
                adaptUsernameColor(newColor, isLightTheme)
            } else {
                newColor
            }.also { savedColors[color] = it }
        }
    }

    private fun adaptUsernameColor(color: Int, isLightTheme: Boolean): Int {
        val colorArray = FloatArray(3)
        ColorUtils.colorToHSL(color, colorArray)
        if (isLightTheme) {
            val luminanceMax = 0.75f -
                    maxOf(1f - ((colorArray[0] - GREEN_HUE_DEGREES) / 100f).pow(2f), RED_HUE_DEGREES) * 0.4f
            colorArray[2] = minOf(colorArray[2], luminanceMax)
        } else {
            val distToRed = RED_HUE_DEGREES - colorArray[0]
            val distToBlue = BLUE_HUE_DEGREES - colorArray[0]
            val normDistanceToRed = distToRed - TWO_PI_DEGREES * floor((distToRed + PI_DEGREES) / TWO_PI_DEGREES)
            val normDistanceToBlue = distToBlue - TWO_PI_DEGREES * floor((distToBlue + PI_DEGREES) / TWO_PI_DEGREES)

            val luminanceMin = 0.3f +
                    maxOf((1f - (normDistanceToBlue / 40f).pow(2f)) * 0.35f, RED_HUE_DEGREES) +
                    maxOf((1f - (normDistanceToRed / 40f).pow(2f)) * 0.1f, RED_HUE_DEGREES)
            colorArray[2] = maxOf(colorArray[2], luminanceMin)
        }

        return ColorUtils.HSLToColor(colorArray)
    }

    private fun prepareEmotes(chatMessage: ChatMessage, message: String, builder: SpannableStringBuilder, startIndex: Int, images: ArrayList<Image>, imageClick: ((String?, String?, String?, Boolean?, Int?, Boolean?, String?) -> Unit)?, useReadableColors: Boolean, isLightTheme: Boolean, enableOverlayEmotes: Boolean, useBoldNames: Boolean, loggedInUser: String?, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?, savedColors: HashMap<String, Int>, localTwitchEmotes: List<TwitchEmote>, showPersonalEmotes: Boolean, personalEmoteSets: Map<String, List<Emote>>, stvUser: STVUser?, thirdPartyEmotes: List<Emote>, cheerEmotes: List<CheerEmote>, savedLocalTwitchEmotes: MutableMap<String, ByteArray>, savedLocalCheerEmotes: MutableMap<String, ByteArray>, savedLocalEmotes: MutableMap<String, ByteArray>): Boolean {
        var wasMentioned = false
        try {
            var builderIndex = startIndex
            val split = builder.substring(builderIndex).split(" ")
            var previousImage: Image? = null
            val twitchEmotes = chatMessage.emotes?.map {
                val realBegin = message.offsetByCodePoints(0, it.begin)
                val realEnd = if (it.begin == realBegin) {
                    it.end
                } else {
                    it.end + realBegin - it.begin
                }
                localTwitchEmotes.find { emote -> emote.id == it.id }?.let { emote ->
                    TwitchEmote(
                        id = emote.id,
                        name = emote.name,
                        localData = emote.localData,
                        format = emote.format,
                        isAnimated = emote.isAnimated,
                        begin = realBegin,
                        end = realEnd,
                        setId = emote.setId,
                        ownerId = emote.ownerId
                    )
                } ?: TwitchEmote(id = it.id, begin = realBegin, end = realEnd)
            }?.sortedBy { it.begin }?.toMutableList()
            val personalEmotes = if (showPersonalEmotes) {
                stvUser?.emoteSetId?.let { setId ->
                    synchronized(personalEmoteSets) {
                        personalEmoteSets.entries.find { it.key == setId }?.value
                    }
                }
            } else null
            for (value in split) {
                if (chatMessage.bits != null) {
                    val bitsCount = value.takeLastWhile { it.isDigit() }
                    val bitsName = value.substringBeforeLast(bitsCount)
                    if (bitsCount.isNotEmpty()) {
                        val emote = synchronized(cheerEmotes) {
                            cheerEmotes.findLast { it.name.equals(bitsName, true) && it.minBits <= bitsCount.toInt() }
                        }
                        if (emote != null) {
                            builder.replace(builderIndex, builderIndex + bitsName.length, ".")
                            builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            if (imageClick != null) {
                                builder.setSpan(object : ClickableSpan() {
                                    override fun onClick(widget: View) {
                                        imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, value, emote.format, emote.isAnimated, null, null, null)
                                    }

                                    override fun updateDrawState(ds: TextPaint) {}
                                }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            images.add(Image(
                                localData = emote.localData?.let { getLocalEmoteData(emote.name + emote.minBits, it, savedLocalCheerEmotes, chatUrl, getEmoteBytes) },
                                url1x = emote.url1x,
                                url2x = emote.url2x,
                                url3x = emote.url3x,
                                url4x = emote.url4x,
                                format = emote.format,
                                isAnimated = emote.isAnimated,
                                size = Image.IMAGE_SIZE_EMOTE,
                                start = builderIndex,
                                end = builderIndex + 1
                            ))
                            builderIndex += 1
                            if (!emote.color.isNullOrBlank()) {
                                builder.setSpan(ForegroundColorSpan(getSavedColor(emote.color, savedColors, useReadableColors, isLightTheme)), builderIndex, builderIndex + bitsCount.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            if (!twitchEmotes.isNullOrEmpty()) {
                                val removed = bitsName.length - 1
                                twitchEmotes.forEach {
                                    it.begin -= removed
                                    it.end -= removed
                                }
                            }
                            previousImage = null
                            builderIndex += bitsCount.length + 1
                            continue
                        }
                    }
                }
                val emote = personalEmotes?.find {
                    it.name == value
                } ?: synchronized(thirdPartyEmotes) {
                    thirdPartyEmotes.find { it.name == value }
                }
                if (emote != null) {
                    if (emote.isOverlayEmote && enableOverlayEmotes && previousImage != null) {
                        builder.replace(builderIndex - 1, builderIndex + value.length, "")
                        val image = Image(
                            localData = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl, getEmoteBytes) },
                            url1x = emote.url1x,
                            url2x = emote.url2x,
                            url3x = emote.url3x,
                            url4x = emote.url4x,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            size = Image.IMAGE_SIZE_EMOTE,
                            thirdParty = emote.thirdParty,
                            fallbackText = emote.name?.takeIf { emote.thirdParty },
                            start = previousImage.start,
                            end = previousImage.end
                        )
                        if (!twitchEmotes.isNullOrEmpty()) {
                            val removed = value.length + 1
                            twitchEmotes.forEach {
                                it.begin -= removed
                                it.end -= removed
                            }
                        }
                        previousImage.overlayEmote = image
                        previousImage = image
                        continue
                    } else {
                        builder.replace(builderIndex, builderIndex + value.length, ".")
                        builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (imageClick != null) {
                            builder.setSpan(object : ClickableSpan() {
                                override fun onClick(widget: View) {
                                    imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, emote.name, emote.format, emote.isAnimated, emote.source, emote.thirdParty, null)
                                }

                                override fun updateDrawState(ds: TextPaint) {}
                            }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        val image = Image(
                            localData = emote.localData?.let { getLocalEmoteData(emote.name!!, it, savedLocalEmotes, chatUrl, getEmoteBytes) },
                            url1x = emote.url1x,
                            url2x = emote.url2x,
                            url3x = emote.url3x,
                            url4x = emote.url4x,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            size = Image.IMAGE_SIZE_EMOTE,
                            thirdParty = emote.thirdParty,
                            fallbackText = emote.name?.takeIf { emote.thirdParty },
                            start = builderIndex,
                            end = builderIndex + 1
                        )
                        images.add(image)
                        if (!twitchEmotes.isNullOrEmpty()) {
                            val removed = value.length - 1
                            twitchEmotes.forEach {
                                it.begin -= removed
                                it.end -= removed
                            }
                        }
                        previousImage = image
                        builderIndex += 2
                        continue
                    }
                }
                val twitchEmote = twitchEmotes?.firstOrNull()?.let { first ->
                    val messageIndex = builderIndex - startIndex
                    when {
                        first.begin == messageIndex -> first
                        first.begin < messageIndex -> {
                            twitchEmotes.remove(first)
                            twitchEmotes.firstOrNull()?.takeIf { it.begin == messageIndex }
                        }
                        else -> null
                    }
                }
                if (twitchEmote != null) {
                    twitchEmotes.remove(twitchEmote)
                    builder.replace(builderIndex, builderIndex + value.length, ".")
                    builder.setSpan(ForegroundColorSpan(Color.TRANSPARENT), builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    val emote = localTwitchEmotes.find { emote -> emote.id == twitchEmote.id }?.let { emote ->
                        TwitchEmote(
                            id = emote.id,
                            name = emote.name,
                            localData = emote.localData,
                            format = emote.format,
                            isAnimated = emote.isAnimated,
                            begin = builderIndex,
                            end = builderIndex + 1,
                            setId = emote.setId,
                            ownerId = emote.ownerId
                        )
                    } ?: TwitchEmote(id = twitchEmote.id)
                    if (imageClick != null) {
                        builder.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) {
                                imageClick(emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x, value, emote.format, emote.isAnimated, null, null, emote.id)
                            }

                            override fun updateDrawState(ds: TextPaint) {}
                        }, builderIndex, builderIndex + 1, SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    val image = Image(
                        localData = emote.localData?.let { getLocalEmoteData(emote.id!!, it, savedLocalTwitchEmotes, chatUrl, getEmoteBytes) },
                        url1x = emote.url1x,
                        url2x = emote.url2x,
                        url3x = emote.url3x,
                        url4x = emote.url4x,
                        format = emote.format,
                        isAnimated = emote.isAnimated,
                        size = Image.IMAGE_SIZE_EMOTE,
                        start = builderIndex,
                        end = builderIndex + 1
                    )
                    images.add(image)
                    if (twitchEmotes.isNotEmpty()) {
                        val removed = value.length - 1
                        twitchEmotes.forEach {
                            it.begin -= removed
                            it.end -= removed
                        }
                    }
                    previousImage = image
                    builderIndex += 2
                    continue
                }
                if (Patterns.WEB_URL.matcher(value).matches()) {
                    val url = if (value.startsWith("http")) value else "https://$value"
                    builder.setSpan(URLSpan(url), builderIndex, builderIndex + value.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                    previousImage = null
                    builderIndex += value.length + 1
                    continue
                }
                if (value.startsWith('@') && useBoldNames) {
                    builder.setSpan(StyleSpan(Typeface.BOLD), builderIndex, builderIndex + value.length, SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (!wasMentioned &&
                    !loggedInUser.isNullOrBlank() &&
                    value.contains(loggedInUser, true) &&
                    chatMessage.userId != null &&
                    chatMessage.userLogin != loggedInUser
                ) {
                    wasMentioned = true
                }
                previousImage = null
                builderIndex += value.length + 1
            }
        } catch (e: Exception) {

        }
        return wasMentioned
    }

    private fun getLocalEmoteData(name: String, data: Pair<Long, Int>, savedLocalEmotes: MutableMap<String, ByteArray>, chatUrl: String?, getEmoteBytes: ((String, Pair<Long, Int>) -> ByteArray?)?): ByteArray? {
        return savedLocalEmotes[name] ?: chatUrl?.let { url ->
            getEmoteBytes?.let { get ->
                get(url, data)?.also {
                    if (savedLocalEmotes.size >= 100) {
                        savedLocalEmotes.remove(savedLocalEmotes.keys.first())
                    }
                    savedLocalEmotes[name] = it
                }
            }
        }
    }

    fun loadImages(fragment: Fragment, itemView: View, bind: (SpannableStringBuilder) -> Unit, images: List<Image>, imagePaint: NamePaint?, userName: String?, userNameStartIndex: Int?, backgroundColor: Int, imageLibrary: String?, builder: SpannableStringBuilder, translated: Boolean, emoteSize: Int, badgeSize: Int, gifSize: Int, emoteQuality: String, animateGifs: Boolean, enableOverlayEmotes: Boolean, chatMessage: ChatMessage, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean, showLanguageDownloadDialog: (ChatMessage, String) -> Unit, hideErrors: Boolean, isCurrent: () -> Boolean = { true }) {
        if (imagePaint != null) {
            if (imageLibrary == "0") {
                fragment.requireContext().imageLoader.enqueue(
                    ImageRequest.Builder(fragment.requireContext()).apply {
                        data(imagePaint.imageUrl)
                        httpHeaders(NetworkHeaders.Builder().apply {
                            add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                        }.build())
                        target(
                            onSuccess = {
                                if (isCurrent()) {
                                (it.asDrawable(fragment.resources)).let { result ->
                                    if (result is Animatable && animateGifs) {
                                        result.callback = object : Drawable.Callback {
                                            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                                itemView.removeCallbacks(what)
                                            }

                                            override fun invalidateDrawable(who: Drawable) {
                                                itemView.invalidate()
                                            }

                                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                                itemView.postDelayed(what, `when`)
                                            }
                                        }
                                        (result as Animatable).start()
                                    }
                                    try {
                                        builder.setSpan(
                                            NamePaintImageSpan(
                                                userName!!,
                                                imagePaint.shadows,
                                                (itemView.background as? ColorDrawable)?.color,
                                                backgroundColor,
                                                result
                                            ),
                                            userNameStartIndex!!,
                                            userNameStartIndex + userName.length,
                                            SPAN_EXCLUSIVE_EXCLUSIVE
                                        )
                                    } catch (e: IndexOutOfBoundsException) {
                                    }
                                    if (!translated && chatMessage.translatedMessage != null) {
                                        addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                                    }
                                    bind(builder)
                                }
                                }
                            },
                        )
                    }.build()
                )
            } else {
                Glide.with(fragment)
                    .load(GlideUrl(imagePaint.imageUrl) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) })
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .into(object : CustomTarget<Drawable>() {
                        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                            if (!isCurrent()) return
                            if (resource is Animatable && animateGifs) {
                                resource.callback = object : Drawable.Callback {
                                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                                        itemView.removeCallbacks(what)
                                    }

                                    override fun invalidateDrawable(who: Drawable) {
                                        itemView.invalidate()
                                    }

                                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                                        itemView.postDelayed(what, `when`)
                                    }
                                }
                                (resource as Animatable).start()
                            }
                            try {
                                builder.setSpan(
                                    NamePaintImageSpan(
                                        userName!!,
                                        imagePaint.shadows,
                                        (itemView.background as? ColorDrawable)?.color,
                                        backgroundColor,
                                        resource
                                    ),
                                    userNameStartIndex!!,
                                    userNameStartIndex + userName.length,
                                    SPAN_EXCLUSIVE_EXCLUSIVE
                                )
                            } catch (e: IndexOutOfBoundsException) {
                            }
                            if (!translated && chatMessage.translatedMessage != null) {
                                addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                            }
                            bind(builder)
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                        }
                    })
            }
        }
        images.forEach { image ->
            loadImage(imageLibrary, fragment, image, emoteQuality, onFailed = {
                if (!isCurrent()) return@loadImage
                val fallbackText = image.fallbackText ?: return@loadImage
                builder.getSpans(image.start, image.end, ForegroundColorSpan::class.java).forEach {
                    builder.removeSpan(it)
                }
                builder.setSpan(object : ReplacementSpan() {
                    override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
                        return paint.measureText(fallbackText).toInt()
                    }
                    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
                        canvas.drawText(fallbackText, x, y.toFloat(), paint)
                    }
                }, image.start, image.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                bind(builder)
            }) { result ->
                if (!isCurrent()) return@loadImage
                val imageSize = when (image.size) {
                    Image.IMAGE_SIZE_EMOTE -> emoteSize
                    Image.IMAGE_SIZE_GIF -> gifSize
                    else -> badgeSize
                }
                val widthRatio = result.intrinsicWidth.toFloat() / result.intrinsicHeight.toFloat()
                val size = if (widthRatio == 1f) {
                    imageSize to imageSize
                } else {
                    (imageSize * widthRatio).toInt() to imageSize
                }
                result.setBounds(0, 0, size.first, size.second)
                if (result is Animatable && image.isAnimated && animateGifs) {
                    result.callback = object : Drawable.Callback {
                        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                            itemView.removeCallbacks(what)
                        }

                        override fun invalidateDrawable(who: Drawable) {
                            itemView.invalidate()
                        }

                        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                            itemView.postDelayed(what, `when`)
                        }
                    }
                    (result as Animatable).start()
                }
                if (image.overlayEmote != null) {
                    val drawables = arrayOf(result)
                    nextOverlayEmote(imageLibrary, fragment, drawables, image.overlayEmote!!, image, itemView, bind, builder, translated, emoteSize, emoteQuality, animateGifs, enableOverlayEmotes, chatMessage, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors, isCurrent)
                } else {
                    builder.setSpan(CenteredImageSpan(result), image.start, image.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (!translated && chatMessage.translatedMessage != null) {
                        addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                    }
                    bind(builder)
                }
            }
        }
    }

    private fun nextOverlayEmote(imageLibrary: String?, fragment: Fragment, drawables: Array<Drawable>, image: Image, bottomImage: Image, itemView: View, bind: (SpannableStringBuilder) -> Unit, builder: SpannableStringBuilder, translated: Boolean, emoteSize: Int, emoteQuality: String, animateGifs: Boolean, enableOverlayEmotes: Boolean, chatMessage: ChatMessage, savedColors: HashMap<String, Int>, useReadableColors: Boolean, isLightTheme: Boolean, showLanguageDownloadDialog: (ChatMessage, String) -> Unit, hideErrors: Boolean, isCurrent: () -> Boolean) {
        loadImage(imageLibrary, fragment, image, emoteQuality, onFailed = {}) { result ->
            if (!isCurrent()) return@loadImage
            val widthRatio = result.intrinsicWidth.toFloat() / result.intrinsicHeight.toFloat()
            val size = if (widthRatio == 1f) {
                emoteSize to emoteSize
            } else {
                (emoteSize * widthRatio).toInt() to emoteSize
            }
            result.setBounds(0, 0, size.first, size.second)
            if (result is Animatable && image.isAnimated && animateGifs) {
                result.callback = object : Drawable.Callback {
                    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                        itemView.removeCallbacks(what)
                    }

                    override fun invalidateDrawable(who: Drawable) {
                        itemView.invalidate()
                    }

                    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
                        itemView.postDelayed(what, `when`)
                    }
                }
                (result as Animatable).start()
            }
            val array = drawables.plus(result)
            if (image.overlayEmote != null) {
                nextOverlayEmote(imageLibrary, fragment, array, image.overlayEmote!!, bottomImage, itemView, bind, builder, translated, emoteSize, emoteQuality, animateGifs, enableOverlayEmotes, chatMessage, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors, isCurrent)
            } else {
                val layer = LayerDrawable(array)
                val width = array.maxOf { it.bounds.right }
                val height = array.maxOf { it.bounds.bottom }
                layer.setBounds(0, 0, width, height)
                builder.setSpan(CenteredImageSpan(layer), bottomImage.start, bottomImage.end, SPAN_EXCLUSIVE_EXCLUSIVE)
                if (!translated && chatMessage.translatedMessage != null) {
                    addTranslation(chatMessage, builder, builder.length, savedColors, useReadableColors, isLightTheme, showLanguageDownloadDialog, hideErrors)
                }
                bind(builder)
            }
        }
    }

    private fun loadImage(imageLibrary: String?, fragment: Fragment, image: Image, emoteQuality: String, onFailed: () -> Unit, onLoaded: (Drawable) -> Unit) {
        if (imageLibrary == "0" || (imageLibrary == "1" && !image.format.equals("webp", true))) {
            loadCoil(fragment, image, emoteQuality, onFailed, onLoaded)
        } else {
            loadGlide(fragment, image, emoteQuality, onFailed, onLoaded)
        }
    }

    private fun loadCoil(fragment: Fragment, image: Image, emoteQuality: String, onFailed: () -> Unit, onLoaded: (Drawable) -> Unit) {
        fragment.requireContext().imageLoader.enqueue(
            ImageRequest.Builder(fragment.requireContext()).apply {
                data(image.localData ?: when (emoteQuality) {
                    "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
                    "3" -> image.url3x ?: image.url2x ?: image.url1x
                    "2" -> image.url2x ?: image.url1x
                    else -> image.url1x
                })
                if (image.thirdParty) {
                    httpHeaders(NetworkHeaders.Builder().apply {
                        add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME)
                    }.build())
                }
                target(
                    onSuccess = {
                        onLoaded((it.asDrawable(fragment.resources)))
                    },
                    onError = {
                        onFailed()
                    },
                )
            }.build()
        )
    }

    private fun loadGlide(fragment: Fragment, image: Image, emoteQuality: String, onFailed: () -> Unit, onLoaded: (Drawable) -> Unit) {
        Glide.with(fragment)
            .load(image.localData ?: when (emoteQuality) {
                "4" -> image.url4x ?: image.url3x ?: image.url2x ?: image.url1x
                "3" -> image.url3x ?: image.url2x ?: image.url1x
                "2" -> image.url2x ?: image.url1x
                else -> image.url1x
            }.let {
                if (image.thirdParty) {
                    GlideUrl(it) { mapOf("User-Agent" to "Xtra/" + BuildConfig.VERSION_NAME) }
                } else it
            })
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    onLoaded(resource)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    onFailed()
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                }
            })
    }
}