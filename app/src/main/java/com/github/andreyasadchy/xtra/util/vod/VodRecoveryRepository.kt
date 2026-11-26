package com.github.andreyasadchy.xtra.util.vod

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Finds Twitch VOD HLS playlists whose metadata remains available after a VOD
 * has been removed from the public video catalogue.
 *
 * This does not bypass Twitch deletion: the returned playlist is accepted only
 * when at least one media segment can still be reached.
 */
object VodRecoveryRepository {
    private val vodHosts = listOf(
        "https://vod-secure.twitch.tv",
        "https://vod-metro.twitch.tv",
        "https://d2e2de1etea730.cloudfront.net",
        "https://dqrpb9wgowsf5.cloudfront.net",
        "https://ds0h3roq6wcgc.cloudfront.net",
        "https://d2nvs31859zcd8.cloudfront.net"
    )

    data class RecoveredVod(
        val streamerLogin: String,
        val streamId: Long,
        val startTimestamp: Long,
        val masterPlaylistUrl: String,
        val qualities: List<RecoveredQuality>
    )

    data class RecoveredQuality(
        val name: String,
        val resolution: Int?,
        val frameRate: Float?,
        val bitrate: Int?,
        val url: String
    )

    /**
     * [timestampIsMinuteOnly] enables a bounded 60-second timestamp search.
     * Cancellation is checked between every network request.
     */
    suspend fun recover(
        streamerLogin: String,
        streamId: Long,
        startTimestamp: Long,
        timestampIsMinuteOnly: Boolean = false
    ): RecoveredVod? = withContext(Dispatchers.IO) {
        val login = streamerLogin.trim().lowercase()
        require(login.matches(Regex("[a-z0-9_]{1,25}"))) { "Enter a valid Twitch channel login." }
        require(streamId > 0) { "Enter a valid stream ID." }
        require(startTimestamp > 0) { "Enter a valid Unix start timestamp." }

        val offsets = if (timestampIsMinuteOnly) 0L..59L else 0L..0L
        for (offset in offsets) {
            coroutineContext.ensureActive()
            val timestamp = startTimestamp + offset
            val path = buildPlaylistPath(login, streamId, timestamp)
            for (host in vodHosts) {
                coroutineContext.ensureActive()
                val masterUrl = "$host$path"
                val master = fetchPlaylist(masterUrl) ?: continue
                val qualities = parseQualities(master, masterUrl)
                if (qualities.isEmpty()) continue

                // A master playlist alone can survive after Twitch removes its media.
                // Require a reachable media playlist and at least one media segment.
                val playable = qualities.filter { quality ->
                    coroutineContext.ensureActive()
                    val media = fetchPlaylist(quality.url) ?: return@filter false
                    val segment = media.lineSequence()
                        .map(String::trim)
                        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?: return@filter false
                    val segmentUrl = runCatching { URL(URL(quality.url), segment).toString() }.getOrNull()
                        ?: return@filter false
                    isReachableMedia(segmentUrl)
                }
                if (playable.isNotEmpty()) {
                    return@withContext RecoveredVod(login, streamId, timestamp, masterUrl, playable)
                }
            }
        }
        null
    }

    internal fun buildPlaylistPath(login: String, streamId: Long, timestamp: Long): String {
        val base = "${login.lowercase()}_${streamId}_${timestamp}"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(base.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(20)
        return "/${digest}_${base}/chunked/index-dvr.m3u8"
    }

    private fun parseQualities(master: String, masterUrl: String): List<RecoveredQuality> {
        val lines = master.lineSequence().map(String::trim).toList()
        val result = mutableListOf<RecoveredQuality>()
        var attributes: String? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                attributes = line.substringAfter(':')
            } else if (attributes != null && line.isNotEmpty() && !line.startsWith("#")) {
                val url = runCatching { URL(URL(masterUrl), line).toString() }.getOrNull()
                if (url != null && url.startsWith("https://")) {
                    val resolution = Regex("""RESOLUTION=\d+x(\d+)""").find(attributes)?.groupValues?.get(1)?.toIntOrNull()
                    val frameRate = Regex("""FRAME-RATE=([\d.]+)""").find(attributes)?.groupValues?.get(1)?.toFloatOrNull()
                    val bitrate = Regex("""BANDWIDTH=(\d+)""").find(attributes)?.groupValues?.get(1)?.toIntOrNull()
                    val name = resolution?.let { "${it}p" } ?: "source"
                    result.add(RecoveredQuality(name, resolution, frameRate, bitrate, url))
                }
                attributes = null
            }
        }
        return result.distinctBy { it.url }.sortedByDescending { it.bitrate ?: 0 }
    }

    private fun fetchPlaylist(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Range", "bytes=0-65535")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { reader ->
                val text = reader.readText().take(65536)
                text.takeIf { it.startsWith("#EXTM3U") }
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun isReachableMedia(url: String): Boolean {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2500
            readTimeout = 2500
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Range", "bytes=0-0")
        }
        return try {
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
