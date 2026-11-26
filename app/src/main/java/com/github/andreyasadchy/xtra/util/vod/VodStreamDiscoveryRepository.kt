package com.github.andreyasadchy.xtra.util.vod

import android.content.Context
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Discovers stream metadata before attempting recovery. A candidate is included
 * only when its actual stream ID and UTC start timestamp are known.
 */
object VodStreamDiscoveryRepository {

    data class StreamCandidate(
        val streamerLogin: String,
        val streamId: Long,
        val startTimestamp: Long,
        val title: String,
        val durationSeconds: Long?
    )

    suspend fun search(context: Context, streamerLogin: String): List<StreamCandidate> =
        withContext(Dispatchers.IO) {
            val login = streamerLogin.trim().removePrefix("@").lowercase(Locale.ROOT)
            require(login.matches(Regex("[a-z0-9_]{1,25}"))) {
                "Enter a valid Twitch channel login."
            }

            coroutineScope {
                // Twitch's own video catalogue contains broadcasts that are still
                // publicly available. Do not label those as deleted/recoverable.
                val twitch = async { runCatching { fetchTwitchVideos(context, login) }.getOrNull().orEmpty() }
                val archive = async { runCatching { fetchArchiveStreams(login) }.getOrNull().orEmpty() }
                val publicStreamIds = twitch.await().mapTo(HashSet()) { it.streamId }
                archive.await()
                    .filter { it.streamId > 0L && it.startTimestamp > 0L }
                    .filterNot { it.streamId in publicStreamIds }
                    .distinctBy { it.streamId }
                    .sortedByDescending { it.startTimestamp }
            }
        }

    private fun fetchTwitchVideos(context: Context, login: String): List<StreamCandidate> {
        val query = """
            query(${'$'}login: String!, ${'$'}first: Int!) {
              user(login: ${'$'}login) {
                videos(first: ${'$'}first) {
                  edges {
                    node {
                      title
                      lengthSeconds
                      createdAt
                      publishedAt
                      previewThumbnailURL
                      animatedPreviewURL
                    }
                  }
                }
              }
            }
        """.trimIndent()
        val body = JSONObject()
            .put("query", query)
            .put("variables", JSONObject().put("login", login).put("first", 100))
            .toString()
        val response = JSONObject(request(
            "https://gql.twitch.tv/gql",
            "POST",
            body,
            TwitchApiHelper.getGQLHeaders(context, true)
        ))
        val edges = response.optJSONObject("data")
            ?.optJSONObject("user")
            ?.optJSONObject("videos")
            ?.optJSONArray("edges") ?: return emptyList()

        return buildList {
            for (index in 0 until edges.length()) {
                val node = edges.optJSONObject(index)?.optJSONObject("node") ?: continue
                val preview = node.optString("previewThumbnailURL")
                val animated = node.optString("animatedPreviewURL")
                val extracted = extractStreamCoordinates(login, preview, animated) ?: continue
                val (streamId, timestamp) = extracted
                add(
                    StreamCandidate(
                        login,
                        streamId,
                        timestamp,
                        node.optString("title"),
                        node.optLong("lengthSeconds").takeIf { it > 0L }
                    )
                )
            }
        }
    }

    private fun fetchArchiveStreams(login: String): List<StreamCandidate> {
        val array = JSONArray(request(
            "https://api.vodvod.top/channels/@$login",
            "GET"
        ))
        return buildList {
            for (index in 0 until array.length()) {
                val metadata = array.optJSONObject(index)
                    ?.optJSONObject("Metadata") ?: continue
                val streamId = metadata.optString("StreamID").toLongOrNull() ?: continue
                val start = parseTimestamp(metadata.optString("StartTime")) ?: continue
                val duration = metadata.optJSONObject("HlsDurationSeconds")
                    ?.takeIf { it.optBoolean("Valid") }
                    ?.optDouble("Float64")
                    ?.takeIf { it > 0.0 }
                    ?.toLong()
                add(
                    StreamCandidate(
                        login,
                        streamId,
                        start,
                        metadata.optString("TitleAtStart"),
                        duration
                    )
                )
            }
        }
    }

    private fun extractStreamCoordinates(login: String, vararg urls: String): Pair<Long, Long>? {
        val pattern = Regex(
            "_${Regex.escape(login)}_(\\d+)_(\\d+)",
            RegexOption.IGNORE_CASE
        )
        for (url in urls) {
            val match = pattern.find(url) ?: continue
            val streamId = match.groupValues[1].toLongOrNull() ?: continue
            val timestamp = match.groupValues[2].toLongOrNull() ?: continue
            return streamId to timestamp
        }
        return null
    }

    private fun parseTimestamp(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching { Instant.parse(value).epochSecond }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant().epochSecond }.getOrNull()
    }

    private fun request(
        address: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "Xtra Android")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            if (connection.responseCode !in 200..299) return ""
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
