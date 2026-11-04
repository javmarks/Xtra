package com.github.andreyasadchy.xtra.ui.chat

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.model.chat.ChatMessage

class MergedChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class Entry(val owner: ChatAdapter, var renderer: ChatAdapter, val message: ChatMessage)

    private val entries = mutableListOf<Entry>()
    private val sources = linkedSetOf<ChatAdapter>()
    private val sourcesByChannelId = mutableMapOf<String, ChatAdapter>()

    override fun getItemViewType(position: Int): Int {
        val renderer = entries.getOrNull(position)?.renderer ?: return 0
        return sources.indexOf(renderer).takeIf { it >= 0 } ?: 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val source = sources.elementAtOrNull(viewType) ?: sources.firstOrNull()
        return source?.onCreateViewHolder(parent, 0)
            ?: object : RecyclerView.ViewHolder(TextView(parent.context)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = entries.getOrNull(position) ?: return
        (holder as? ChatAdapter.ViewHolder)?.let { entry.renderer.bindMessage(it, entry.message) }
    }

    override fun getItemCount(): Int = entries.size

    fun addSource(source: ChatAdapter, channelId: String?, messages: List<ChatMessage>) {
        sources.add(source)
        if (!channelId.isNullOrBlank()) sourcesByChannelId[channelId] = source
        entries.forEach { it.renderer = rendererFor(it.owner, it.message) }
        replaceSourceMessages(source, messages)
    }

    private fun rendererFor(owner: ChatAdapter, message: ChatMessage): ChatAdapter {
        val originId = message.sourceRoomId ?: message.roomId
        return if (originId.isNullOrBlank()) owner else sourcesByChannelId[originId] ?: owner
    }

    fun addMessage(owner: ChatAdapter, message: ChatMessage) {
        if (entries.any { it.owner === owner && it.message.id != null && it.message.id == message.id }) return
        val oldSize = entries.size
        entries.add(Entry(owner, rendererFor(owner, message), message))
        entries.sortBy { it.message.timestamp ?: Long.MAX_VALUE }
        val index = entries.indexOfFirst { it.owner === owner && it.message === message }
        if (index >= 0 && index == oldSize) notifyItemInserted(index) else notifyDataSetChanged()
    }

    fun addOlderMessages(owner: ChatAdapter, messages: List<ChatMessage>) {
        var changed = false
        messages.forEach { message ->
            val duplicate = entries.any {
                it.owner === owner && it.message.id != null && it.message.id == message.id
            }
            if (!duplicate) {
                entries.add(Entry(owner, rendererFor(owner, message), message))
                changed = true
            }
        }
        if (changed) {
            entries.sortBy { it.message.timestamp ?: Long.MAX_VALUE }
            notifyDataSetChanged()
        }
    }

    fun replaceSourceMessages(owner: ChatAdapter, messages: List<ChatMessage>) {
        entries.removeAll { it.owner === owner }
        messages.forEach { message -> entries.add(Entry(owner, rendererFor(owner, message), message)) }
        entries.sortBy { it.message.timestamp ?: Long.MAX_VALUE }
        notifyDataSetChanged()
    }

    fun removeOldestMessages(owner: ChatAdapter, count: Int) {
        if (count <= 0) return
        val indexes = entries.indices.filter { entries[it].owner === owner }.take(count)
        indexes.asReversed().forEach { entries.removeAt(it) }
        if (indexes.isNotEmpty()) notifyDataSetChanged()
    }

    fun clearSource(owner: ChatAdapter) {
        if (entries.removeAll { it.owner === owner }) notifyDataSetChanged()
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val entry = entries.getOrNull(holder.bindingAdapterPosition) ?: return
        (holder as? ChatAdapter.ViewHolder)?.let { entry.renderer.onViewAttachedToWindow(it) }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        val entry = entries.getOrNull(holder.bindingAdapterPosition)
        if (entry != null) {
            (holder as? ChatAdapter.ViewHolder)?.let { entry.renderer.onViewDetachedFromWindow(it) }
        }
        super.onViewDetachedFromWindow(holder)
    }
}
