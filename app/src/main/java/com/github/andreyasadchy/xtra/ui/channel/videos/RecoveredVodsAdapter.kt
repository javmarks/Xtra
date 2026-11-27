package com.github.andreyasadchy.xtra.ui.channel.videos

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.vod.RecoveredVodStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecoveredVodsAdapter : RecyclerView.Adapter<RecoveredVodsAdapter.EntryViewHolder>() {
    private var entries: List<RecoveredVodStore.Entry> = emptyList()

    fun submitEntries(value: List<RecoveredVodStore.Entry>) {
        entries = value
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = MaterialCardView(context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(8) }
            radius = dp(8).toFloat()
            useCompatPadding = true
            isClickable = true
            isFocusable = true
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val marker = TextView(context).apply {
            text = "DELETED · RECOVERED"
            textSize = 11f
            isAllCaps = true
            gravity = Gravity.START
        }
        val title = TextView(context).apply {
            textSize = 16f
            maxLines = 2
        }
        val details = TextView(context).apply {
            textSize = 12f
            maxLines = 1
        }
        content.addView(marker)
        content.addView(title)
        content.addView(details)
        card.addView(content)
        return EntryViewHolder(card, marker, title, details)
    }

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        val entry = entries[position]
        holder.title.text = entry.title.ifBlank { "Untitled stream" }
        val date = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
            .format(Date(entry.startTimestamp * 1000L))
        val duration = entry.durationSeconds?.let {
            " · ${it / 3600}h ${(it % 3600) / 60}m"
        }.orEmpty()
        holder.details.text = "$date$duration"
        holder.itemView.setOnClickListener {
            val qualities = entry.qualities
            val context = holder.itemView.context
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                action = MainActivity.INTENT_PLAY_RECOVERED_VOD
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.KEY_RECOVERED_LOGIN, entry.streamerLogin)
                putExtra(MainActivity.KEY_RECOVERED_STREAM_ID, entry.streamId.toString())
                putStringArrayListExtra(MainActivity.KEY_RECOVERED_URLS, ArrayList(qualities.map { it.url }))
                putStringArrayListExtra(MainActivity.KEY_RECOVERED_NAMES, ArrayList(qualities.map { it.name }))
                putExtra(MainActivity.KEY_RECOVERED_RESOLUTIONS, qualities.map { it.resolution ?: 0 }.toIntArray())
                putExtra(MainActivity.KEY_RECOVERED_FRAME_RATES, qualities.map { it.frameRate ?: 0f }.toFloatArray())
                putExtra(MainActivity.KEY_RECOVERED_BITRATES, qualities.map { it.bitrate ?: 0 }.toIntArray())
            })
        }
    }

    class EntryViewHolder(
        itemView: View,
        val marker: TextView,
        val title: TextView,
        val details: TextView
    ) : RecyclerView.ViewHolder(itemView)
}
