package com.github.andreyasadchy.xtra.util.vod

import android.content.Context
import com.github.andreyasadchy.xtra.util.prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small local index of deleted VODs that Xtra has actually verified playable.
 * Entries are kept in app preferences; no database migration is required.
 */
object RecoveredVodStore {
    private const val KEY = "recovered_deleted_vods_v1"
    private const val MAX_ENTRIES = 100

    data class Entry(
        val streamerLogin: String,
        val streamId: Long,
        val startTimestamp: Long,
        val title: String,
        val durationSeconds: Long?,
        val qualities: List<VodRecoveryRepository.RecoveredQuality>
    )

    fun load(context: Context, streamerLogin: String): List<Entry> {
        val login = streamerLogin.trim().removePrefix("@").lowercase()
        val array = runCatching {
            JSONArray(context.prefs().getString(KEY, "[]"))
        }.getOrElse { JSONArray() }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val entry = item.toEntry() ?: continue
                if (entry.streamerLogin.equals(login, true)) add(entry)
            }
        }.sortedByDescending { it.startTimestamp }
    }

    fun save(context: Context, entry: Entry) {
        val array = runCatching {
            JSONArray(context.prefs().getString(KEY, "[]"))
        }.getOrElse { JSONArray() }
        val retained = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val existing = item.toEntry() ?: continue
                if (!(existing.streamerLogin.equals(entry.streamerLogin, true) &&
                            existing.streamId == entry.streamId)) add(existing)
            }
        }.toMutableList()
        retained.add(entry)
        val updated = JSONArray()
        retained.sortedByDescending { it.startTimestamp }.take(MAX_ENTRIES).forEach {
            updated.put(it.toJson())
        }
        context.prefs().edit().putString(KEY, updated.toString()).apply()
    }

    fun remove(context: Context, streamerLogin: String, streamId: Long) {
        val array = runCatching {
            JSONArray(context.prefs().getString(KEY, "[]"))
        }.getOrElse { JSONArray() }
        val updated = JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val entry = item.toEntry() ?: continue
            if (!(entry.streamerLogin.equals(streamerLogin, true) && entry.streamId == streamId)) {
                updated.put(item)
            }
        }
        context.prefs().edit().putString(KEY, updated.toString()).apply()
    }

    private fun Entry.toJson() = JSONObject().apply {
        put("login", streamerLogin)
        put("streamId", streamId)
        put("startTimestamp", startTimestamp)
        put("title", title)
        put("durationSeconds", durationSeconds ?: JSONObject.NULL)
        put("qualities", JSONArray().apply {
            qualities.forEach { quality ->
                put(JSONObject().apply {
                    put("name", quality.name)
                    put("resolution", quality.resolution ?: JSONObject.NULL)
                    put("frameRate", quality.frameRate ?: JSONObject.NULL)
                    put("bitrate", quality.bitrate ?: JSONObject.NULL)
                    put("url", quality.url)
                })
            }
        })
    }

    private fun JSONObject.toEntry(): Entry? {
        val login = optString("login").takeIf { it.isNotBlank() } ?: return null
        val streamId = optLong("streamId").takeIf { it > 0L } ?: return null
        val timestamp = optLong("startTimestamp").takeIf { it > 0L } ?: return null
        val qualityArray = optJSONArray("qualities") ?: return null
        val qualities = buildList {
            for (index in 0 until qualityArray.length()) {
                val item = qualityArray.optJSONObject(index) ?: continue
                val url = item.optString("url").takeIf { it.startsWith("https://") } ?: continue
                add(
                    VodRecoveryRepository.RecoveredQuality(
                        name = item.optString("name", "source"),
                        resolution = item.optInt("resolution").takeIf { !item.isNull("resolution") && it > 0 },
                        frameRate = item.optDouble("frameRate").toFloat().takeIf { !item.isNull("frameRate") && it > 0f },
                        bitrate = item.optInt("bitrate").takeIf { !item.isNull("bitrate") && it > 0 },
                        url = url
                    )
                )
            }
        }
        if (qualities.isEmpty()) return null
        return Entry(
            streamerLogin = login,
            streamId = streamId,
            startTimestamp = timestamp,
            title = optString("title"),
            durationSeconds = optLong("durationSeconds").takeIf { !isNull("durationSeconds") && it > 0L },
            qualities = qualities
        )
    }
}
