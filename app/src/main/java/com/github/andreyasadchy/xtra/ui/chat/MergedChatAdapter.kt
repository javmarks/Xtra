package com.github.andreyasadchy.xtra.ui.chat

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.model.chat.ChatMessage

class MergedChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private data class Entry(val source: ChatAdapter, val message: ChatMessage)

    private val entries = mutableListOf<Entry>()
    private val sources = linkedSetOf<ChatAdapter>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val source = sources.firstOrNull()
        return source?.onCreateViewHolder(parent, viewType)
            ?: object : RecyclerView.ViewHolder(TextView(parent.context)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val entry = entries.getOrNull(position) ?: return
        (holder as? ChatAdapter.ViewHolder)?.let { entry.source.bindMessage(it, entry.message) }
    }

    override fun getItemCount(): Int = entries.size

    fun addSource(source: ChatAdapter, messages: List<ChatMessage>) {
        sources.add(source)
        replaceSourceMessages(source, messages)
    }

    fun addMessage(source: ChatAdapter, message: ChatMessage) {
        if (entries.any { it.source === source && it.message.id != null && it.message.id == message.id }) return
        val oldSize = entries.size
        entries.add(Entry(source, message))
        entries.sortBy { it.message.timestamp ?: Long.MAX_VALUE }
        val index = entries.indexOfFirst { it.source === source && it.message === message }
        if (index >= 0 && index == oldSize) notifyItemInserted(index) else notifyDataSetChanged()
    }

    fun addOlderMessages(source: ChatAdapter, messages: List<ChatMessage>) {
        var changed = false
        messages.forEach { message ->
            val duplicate = entries.any {
                it.source === source && it.message.id != null && it.message.id == message.id
            }
            if (!duplicate) {
                entries.add(Entry(source, message))
                changed = true
            }
        }
        if (changed) {
            entries.sortBy { it.message.timestamp ?: Long.MAX_VALUE }
            notifyDataSetChanged()
        }
    }

    fun replaceSourceMessages(source: ChatAdapter, messages: List<ChatMessage>) {
        entries.removeAll { it.source === source }
        messages.forEach { message -> entries.add(Entry(source, message)) }
        entries.sortBy { it.message.timestamp ?: Long.MAX_VALUE }
        notifyDataSetChanged()
    }

    fun removeOldestMessages(source: ChatAdapter, count: Int) {
        if (count <= 0) return
        val indexes = entries.indices.filter { entries[it].source === source }.take(count)
        indexes.asReversed().forEach { entries.removeAt(it) }
        if (indexes.isNotEmpty()) notifyDataSetChanged()
    }

    fun clearSource(source: ChatAdapter) {
        if (entries.removeAll { it.source === source }) notifyDataSetChanged()
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val entry = entries.getOrNull(holder.bindingAdapterPosition) ?: return
        (holder as? ChatAdapter.ViewHolder)?.let { entry.source.onViewAttachedToWindow(it) }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        val entry = entries.getOrNull(holder.bindingAdapterPosition)
        if (entry != null) {
            (holder as? ChatAdapter.ViewHolder)?.let { entry.source.onViewDetachedFromWindow(it) }
        }
        super.onViewDetachedFromWindow(holder)
    }
}
