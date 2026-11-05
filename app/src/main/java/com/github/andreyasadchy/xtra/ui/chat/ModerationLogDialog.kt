package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.setPadding
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.json.JSONObject

/**
 * Compact, readable moderation activity cards. Older plain-text entries remain supported.
 */
object ModerationLogDialog {
    private data class Entry(
        val action: String,
        val target: String?,
        val moderator: String?,
        val time: String?,
        val channel: String?,
        val duration: String?,
        val reason: String?,
        val message: String?,
        val legacy: String?,
    )

    fun show(context: Context, title: String, rawEntries: List<String>, emptyMessage: String) {
        val builder = context.getAlertDialogBuilder().setTitle(title)
            .setPositiveButton(android.R.string.ok, null)
        if (rawEntries.isEmpty()) {
            builder.setMessage(emptyMessage)
            builder.show()
            return
        }

        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF222222.toInt())
        val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface)
        val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, secondary)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(2))
        }
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4))
        }
        rawEntries.forEach { raw ->
            val entry = parse(raw)
            val card = MaterialCardView(context).apply {
                radius = dp(12).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = outline
                setCardBackgroundColor(surface)
                useCompatPadding = false
                val inner = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12))
                }
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val action = TextView(context).apply {
                    text = entry.action
                    setTextColor(onSurface)
                    textSize = 14f
                    setTypeface(typeface, Typeface.BOLD)
                }
                header.addView(action, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                entry.time?.takeIf { it.isNotBlank() }?.let { time ->
                    header.addView(TextView(context).apply {
                        text = time
                        textSize = 11f
                        setTextColor(secondary)
                    })
                }
                inner.addView(header)
                val targetLine = buildString {
                    if (!entry.target.isNullOrBlank()) append(entry.target)
                    if (!entry.moderator.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("by ").append(entry.moderator)
                    }
                    if (!entry.channel.isNullOrBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("#").append(entry.channel.removePrefix("#"))
                    }
                }
                if (targetLine.isNotBlank()) inner.addView(TextView(context).apply {
                    text = targetLine
                    textSize = 12f
                    setTextColor(secondary)
                    setPadding(0, dp(3), 0, 0)
                })
                entry.duration?.takeIf { it.isNotBlank() }?.let { duration ->
                    inner.addView(detail(context, "Duration", duration, onSurface, secondary, dp(6)))
                }
                entry.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                    inner.addView(detail(context, "Reason", reason, onSurface, secondary, dp(6)))
                }
                entry.message?.takeIf { it.isNotBlank() }?.let { message ->
                    inner.addView(detail(context, "Message", message, onSurface, secondary, dp(6)))
                }
                entry.legacy?.takeIf { it.isNotBlank() }?.let { legacy ->
                    inner.addView(TextView(context).apply {
                        text = legacy
                        textSize = 12f
                        setTextColor(secondary)
                        setPadding(0, dp(4), 0, 0)
                    })
                }
                addView(inner)
            }
            list.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        scroll.addView(list)
        builder.setView(scroll)
        builder.show()
    }

    private fun detail(
        context: Context,
        label: String,
        value: String,
        primary: Int,
        secondary: Int,
        top: Int,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, top, 0, 0)
        addView(TextView(context).apply {
            text = label.uppercase()
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(secondary)
        })
        addView(TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(primary)
        })
    }

    private fun parse(raw: String): Entry {
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json != null && json.has("action")) {
            fun value(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
            val seconds = json.optLong("duration", 0L).takeIf { it > 0L }
            return Entry(
                action = value("action")?.replaceFirstChar { it.uppercase() } ?: "Moderation event",
                target = value("target"),
                moderator = value("moderator"),
                time = value("time"),
                channel = value("channel"),
                duration = value("durationLabel") ?: seconds?.let { "${it}s" },
                reason = value("reason"),
                message = value("message"),
                legacy = null,
            )
        }
        val action = when {
            raw.contains("Timeout", true) -> "Timeout"
            raw.contains("Ban", true) -> "Ban"
            raw.contains("Message deleted", true) -> "Message deleted"
            raw.contains("Chat cleared", true) -> "Chat cleared"
            raw.contains("MOD LOG", true) -> "Moderation action"
            else -> "Chat moderation"
        }
        return Entry(action, null, null, null, null, null, null, null, raw)
    }
}
