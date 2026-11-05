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
    // Twitch message IDs are unique across channels, including copies delivered through Shared Chat.
    private val messageIds = mutableSetOf<String>()

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

    private fun messageKey(message: ChatMessage): String? =
        message.id?.takeIf { it.isNotBlank() }
            ?: message.sourceMessageId?.takeIf { it.isNotBlank() }

    private fun insertSorted(entry: Entry) {
        val timestamp = entry.message.timestamp ?: Long.MAX_VALUE
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val midTimestamp = entries[mid].message.timestamp ?: Long.MAX_VALUE
            if (midTimestamp <= timestamp) low = mid + 1 else high = mid
        }
        entries.add(low, entry)
    }

    private fun rebuildMessageIds() {
        messageIds.clear()
        entries.forEach { entry -> messageKey(entry.message)?.let(messageIds::add) }
    }

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
        // Reply previews are auxiliary rows; the actual chat message carries the reply metadata.
        if (message.type == ChatMessage.REPLY_MESSAGE) return
        val key = messageKey(message)
        if (key != null && !messageIds.add(key)) return

        insertSorted(Entry(owner, rendererFor(owner, message), message))
        val insertedAt = entries.indexOfFirst { it.owner === owner && it.message === message }
        if (insertedAt >= 0) notifyItemInserted(insertedAt)
        trimLiveMessages(owner)
    }

    // Keep the live tail light, while still allowing explicit history pagination to add older rows.
    private fun trimLiveMessages(owner: ChatAdapter) {
        var count = entries.count { it.owner === owner }
        while (count > MAX_LIVE_MESSAGES_PER_SOURCE) {
            val index = entries.indexOfFirst { it.owner === owner }
            if (index < 0) break
            val removed = entries.removeAt(index)
            messageKey(removed.message)?.let(messageIds::remove)
            notifyItemRemoved(index)
            count--
        }
    }

    fun addOlderMessages(owner: ChatAdapter, messages: List<ChatMessage>) {
        var changed = false
        messages.forEach { message ->
            if (message.type == ChatMessage.REPLY_MESSAGE) return@forEach
            val key = messageKey(message)
            if (key == null || messageIds.add(key)) {
                insertSorted(Entry(owner, rendererFor(owner, message), message))
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    fun replaceSourceMessages(owner: ChatAdapter, messages: List<ChatMessage>) {
        entries.removeAll { it.owner === owner }
        rebuildMessageIds()
        messages.asSequence()
            .filter { it.type != ChatMessage.REPLY_MESSAGE }
            .sortedBy { it.timestamp ?: Long.MAX_VALUE }
            .toList()
            .takeLast(MAX_LIVE_MESSAGES_PER_SOURCE)
            .forEach { message ->
                val key = messageKey(message)
                if (key == null || messageIds.add(key)) {
                    insertSorted(Entry(owner, rendererFor(owner, message), message))
                }
            }
        notifyDataSetChanged()
    }

    fun removeOldestMessages(owner: ChatAdapter, count: Int) {
        if (count <= 0) return
        var remaining = count
        val iterator = entries.listIterator()
        while (iterator.hasNext() && remaining > 0) {
            val entry = iterator.next()
            if (entry.owner === owner) {
                messageKey(entry.message)?.let(messageIds::remove)
                iterator.remove()
                remaining--
            }
        }
        if (remaining != count) notifyDataSetChanged()
    }

    fun removeSource(source: ChatAdapter) {
        if (!sources.remove(source)) return
        sourcesByChannelId.entries.removeAll { it.value === source }
        entries.forEach { entry ->
            if (entry.renderer === source) entry.renderer = entry.owner
        }
        entries.removeAll { it.owner === source }
        rebuildMessageIds()
        notifyDataSetChanged()
    }

    fun clearSource(owner: ChatAdapter) {
        if (entries.removeAll { it.owner === owner }) {
            rebuildMessageIds()
            notifyDataSetChanged()
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val entry = entries.getOrNull(position) ?: return
        (holder as? ChatAdapter.ViewHolder)?.let { entry.renderer.onViewAttachedToWindow(it) }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        val position = holder.bindingAdapterPosition
        val entry = if (position == RecyclerView.NO_POSITION) null else entries.getOrNull(position)
        if (entry != null) {
            (holder as? ChatAdapter.ViewHolder)?.let { entry.renderer.onViewDetachedFromWindow(it) }
        }
        super.onViewDetachedFromWindow(holder)
    }

    companion object {
        private const val MAX_LIVE_MESSAGES_PER_SOURCE = 500
    }
}
