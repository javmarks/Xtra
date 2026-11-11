package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Typeface
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import org.json.JSONObject

/** Live, readable moderation activity cards. */
object ModerationLogDialog {
    private data class Entry(
        val action: String, val target: String?, val moderator: String?, val time: String?,
        val channel: String?, val duration: String?, val reason: String?, val message: String?,
        val legacy: String?,
    )

    class Handle internal constructor(
        val dialog: AlertDialog, private val context: Context,
        private val list: LinearLayout, private val emptyMessage: String,
    ) {
        private var renderedEntries: List<String> = emptyList()
        private var visibleLimit = 60
        private var latestEntries: List<String> = emptyList()
        private var footer: TextView? = null

        fun update(rawEntries: List<String>) {
            if (!dialog.isShowing) return
            latestEntries = rawEntries
            footer?.let { list.removeView(it) }
            footer = null
            // Keep rendering lightweight while allowing older saved entries to be loaded on demand.
            val visibleEntries = rawEntries.take(visibleLimit)
            if (visibleEntries == renderedEntries) {
                addLoadOlderFooter(rawEntries.size, visibleEntries.size)
                return
            }
            val density = context.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            if (visibleEntries.isEmpty()) {
                list.removeAllViews()
                renderedEntries = emptyList()
                list.addView(TextView(context).apply {
                    text = emptyMessage
                    textSize = 13f
                    setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt()))
                    setPadding(dp(12))
                })
                return
            }
            val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt())
            val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF222222.toInt())
            val secondary = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface)
            val outline = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, secondary)
            var overlap = minOf(visibleEntries.size, renderedEntries.size)
            while (overlap > 0 && visibleEntries.drop(visibleEntries.size - overlap) != renderedEntries.take(overlap)) {
                overlap--
            }
            val canAppendIncrementally = overlap > 0
            val entriesToRender = if (canAppendIncrementally) {
                // Existing entries are the suffix of the new list; trim entries that
                // fell outside the visible window, then prepend only genuinely new cards.
                while (list.childCount > overlap) list.removeViewAt(list.childCount - 1)
                visibleEntries.take(visibleEntries.size - overlap)
            } else {
                list.removeAllViews()
                visibleEntries
            }
            entriesToRender.forEachIndexed { index, raw ->
                val entry = parse(raw)
                val card = MaterialCardView(context).apply {
                    radius = dp(12).toFloat()
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    strokeColor = outline
                    setCardBackgroundColor(surface)
                    useCompatPadding = false
                    val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12)) }
                    val header = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    header.addView(TextView(context).apply {
                        text = entry.action
                        setTextColor(onSurface)
                        textSize = 14f
                        setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    entry.time?.takeIf { it.isNotBlank() }?.let { time ->
                        header.addView(TextView(context).apply { text = time; textSize = 11f; setTextColor(secondary) })
                    }
                    inner.addView(header)
                    val targetLine = buildString {
                        if (!entry.target.isNullOrBlank()) append(entry.target)
                        if (!entry.moderator.isNullOrBlank()) { if (isNotEmpty()) append(" · "); append("by ").append(entry.moderator) }
                        if (!entry.channel.isNullOrBlank()) { if (isNotEmpty()) append(" · "); append("#").append(entry.channel.removePrefix("#")) }
                    }
                    if (targetLine.isNotBlank()) inner.addView(TextView(context).apply {
                        autoLinkMask = Linkify.WEB_URLS
                        movementMethod = LinkMovementMethod.getInstance()
                        linksClickable = true
                        text = targetLine; textSize = 12f; setTextColor(secondary); setPadding(0, dp(3), 0, 0)
                    })
                    entry.duration?.takeIf { it.isNotBlank() }?.let { inner.addView(detail(context, "Duration", it, onSurface, secondary, dp(6))) }
                    entry.reason?.takeIf { it.isNotBlank() }?.let { inner.addView(detail(context, "Reason", it, onSurface, secondary, dp(6))) }
                    entry.message?.takeIf { it.isNotBlank() }?.let { inner.addView(detail(context, "Message", it, onSurface, secondary, dp(6))) }
                    entry.legacy?.takeIf { it.isNotBlank() }?.let { value ->
                        inner.addView(TextView(context).apply {
                            autoLinkMask = Linkify.WEB_URLS
                            movementMethod = LinkMovementMethod.getInstance()
                            linksClickable = true
                            text = value; textSize = 12f; setTextColor(secondary); setPadding(0, dp(4), 0, 0)
                        })
                    }
                    addView(inner)
                }
                list.addView(card, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
            renderedEntries = visibleEntries
            addLoadOlderFooter(rawEntries.size, visibleEntries.size)
        }

        private fun addLoadOlderFooter(total: Int, visible: Int) {
            if (total <= visible || visibleLimit >= 300) return
            val density = context.resources.displayMetrics.density
            val remaining = (total - visible).coerceAtMost(60)
            val loadOlder = TextView(context).apply {
                text = "Load $remaining older entries"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimary, 0xFF3F51B5.toInt()))
                setPadding((12 * density).toInt())
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    visibleLimit = (visibleLimit + 60).coerceAtMost(300)
                    update(latestEntries)
                }
            }
            footer = loadOlder
            list.addView(loadOlder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun show(context: Context, title: String, rawEntries: List<String>, emptyMessage: String): Handle {
        val density = context.resources.displayMetrics.density
        val scroll = ScrollView(context).apply { isFillViewport = true; clipToPadding = false; setPadding((2 * density).toInt()) }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding((4 * density).toInt()) }
        scroll.addView(list)
        val dialog = context.getAlertDialogBuilder().setTitle(title).setView(scroll)
            .setPositiveButton(android.R.string.ok, null).create()
        val handle = Handle(dialog, context, list, emptyMessage)
        dialog.show()
        handle.update(rawEntries)
        return handle
    }

    private fun detail(context: Context, label: String, value: String, primary: Int, secondary: Int, top: Int) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, top, 0, 0)
            addView(TextView(context).apply { text = label.uppercase(); textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(secondary) })
            addView(TextView(context).apply {
                autoLinkMask = Linkify.WEB_URLS
                movementMethod = LinkMovementMethod.getInstance()
                linksClickable = true
                text = value; textSize = 13f; setTextColor(primary)
            })
        }

    private fun parse(raw: String): Entry {
        val jsonStart = raw.indexOf('{')
        val payload = if (jsonStart >= 0) raw.substring(jsonStart) else raw
        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json != null && json.has("action")) {
            fun value(key: String) = json.optString(key).takeIf { it.isNotBlank() && it != "null" }
            val seconds = json.optLong("duration", 0L).takeIf { it > 0L }
            val prefixChannel = raw.substringBefore(" · ").removePrefix("#").takeIf { jsonStart > 0 }
            return Entry(
                action = value("action")?.replaceFirstChar { it.uppercase() } ?: "Moderation event",
                target = value("target"), moderator = value("moderator"), time = value("time"),
                channel = value("channel") ?: prefixChannel,
                duration = value("durationLabel") ?: seconds?.let { "${it}s" },
                reason = value("reason"), message = value("message"), legacy = null,
            )
        }
        val cleaned = raw.substringAfter(" · ", raw).trim()
        val action = when {
            cleaned.contains("Timeout", true) -> "Timeout"
            cleaned.contains("Ban", true) -> "Ban"
            cleaned.contains("Message deleted", true) -> "Message deleted"
            cleaned.contains("Chat cleared", true) -> "Chat cleared"
            cleaned.contains("7TV LOG", true) -> "Moderation activity"
            else -> "Chat moderation"
        }
        return Entry(action, null, null, null, null, null, null, null, cleaned.replace("7TV LOG · ", "").trim())
    }
}
