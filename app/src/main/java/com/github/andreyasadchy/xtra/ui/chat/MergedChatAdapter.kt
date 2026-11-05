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
    private val sourceEntryCounts = mutableMapOf<ChatAdapter, Int>()
    private val sourceChannelInfo = mutableMapOf<String, Pair<String?, String?>>()

    fun setSourceChannelInfo(channelId: String, channelName: String?, imageUrl: String?) {
        if (channelId.isBlank()) return
        sourceChannelInfo[channelId] = channelName to imageUrl
        entries.forEach { entry ->
            val sourceMessage = if (entry.message.type == ChatMessage.REPLY_MESSAGE) entry.message.replyParent ?: entry.message else entry.message
            if ((sourceMessage.sourceRoomId ?: sourceMessage.roomId) == channelId) {
                entry.message.sourceChannelName = channelName
                entry.message.sourceChannelImageUrl = imageUrl
            }
        }
    }

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

    // Reply preview rows inherit the timestamp of the real message they preview.
    // Their own timestamp is intentionally null, so sorting only by it sent every
    // preview to the bottom of All Chats instead of keeping it beside its reply.
    private fun sortTimestamp(message: ChatMessage): Long =
        message.timestamp ?: message.replyParent?.timestamp ?: Long.MAX_VALUE

    private fun insertSorted(entry: Entry): Int {
        val timestamp = sortTimestamp(entry.message)
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            val midTimestamp = sortTimestamp(entries[mid].message)
            if (midTimestamp <= timestamp) low = mid + 1 else high = mid
        }
        entries.add(low, entry)
        return low
    }

    private fun incrementSourceCount(owner: ChatAdapter) {
        sourceEntryCounts[owner] = (sourceEntryCounts[owner] ?: 0) + 1
    }

    private fun decrementSourceCount(owner: ChatAdapter) {
        val remaining = ((sourceEntryCounts[owner] ?: 0) - 1).coerceAtLeast(0)
        if (remaining == 0) sourceEntryCounts.remove(owner) else sourceEntryCounts[owner] = remaining
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
        // Synthetic reply-preview rows may not carry their own room/channel IDs.
        // Resolve them through the real message they preview so All Chats uses the
        // same channel-specific adapter and rendering settings as the individual chat.
        val sourceMessage = if (message.type == ChatMessage.REPLY_MESSAGE) message.replyParent ?: message else message
        val originId = sourceMessage.sourceRoomId ?: sourceMessage.roomId
        if (!originId.isNullOrBlank()) {
            sourceChannelInfo[originId]?.let { (name, imageUrl) ->
                message.sourceChannelName = name
                message.sourceChannelImageUrl = imageUrl
            }
        }
        return if (originId.isNullOrBlank()) owner else sourcesByChannelId[originId] ?: owner
    }

    fun addMessage(owner: ChatAdapter, message: ChatMessage) {
        val key = messageKey(message)
        if (key != null && !messageIds.add(key)) return

        val insertedAt = insertSorted(Entry(owner, rendererFor(owner, message), message))
        incrementSourceCount(owner)
        notifyItemInserted(insertedAt)
        trimLiveMessages(owner)
    }

    // Keep the live tail light, while still allowing explicit history pagination to add older rows.
    private fun trimLiveMessages(owner: ChatAdapter) {
        var count = sourceEntryCounts[owner] ?: return
        while (count > MAX_LIVE_MESSAGES_PER_SOURCE) {
            val index = entries.indexOfFirst { it.owner === owner }
            if (index < 0) break
            val removed = entries.removeAt(index)
            messageKey(removed.message)?.let(messageIds::remove)
            decrementSourceCount(owner)
            notifyItemRemoved(index)
            count--
        }
    }

    fun addOlderMessages(owner: ChatAdapter, messages: List<ChatMessage>) {
        var changed = false
        messages.forEach { message ->
            val key = messageKey(message)
            if (key == null || messageIds.add(key)) {
                insertSorted(Entry(owner, rendererFor(owner, message), message))
                incrementSourceCount(owner)
                changed = true
            }
        }
        if (changed) notifyDataSetChanged()
    }

    fun replaceSourceMessages(owner: ChatAdapter, messages: List<ChatMessage>) {
        entries.removeAll { it.owner === owner }
        sourceEntryCounts.remove(owner)
        rebuildMessageIds()
        messages.asSequence()
            .sortedBy { sortTimestamp(it) }
            .toList()
            .takeLast(MAX_LIVE_MESSAGES_PER_SOURCE)
            .forEach { message ->
                val key = messageKey(message)
                if (key == null || messageIds.add(key)) {
                    insertSorted(Entry(owner, rendererFor(owner, message), message))
                    incrementSourceCount(owner)
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
                decrementSourceCount(owner)
                remaining--
            }
        }
        if (remaining != count) notifyDataSetChanged()
    }

    fun removeSource(source: ChatAdapter) {
        if (!sources.remove(source)) return
        sourcesByChannelId.entries.removeAll { it.value === source }
        sourceEntryCounts.remove(source)
        entries.forEach { entry ->
            if (entry.renderer === source) entry.renderer = entry.owner
        }
        entries.removeAll { it.owner === source }
        rebuildMessageIds()
        notifyDataSetChanged()
    }

    fun clearSource(owner: ChatAdapter) {
        if (entries.removeAll { it.owner === owner }) {
            sourceEntryCounts.remove(owner)
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
