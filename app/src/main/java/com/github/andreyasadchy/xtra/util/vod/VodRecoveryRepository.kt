package com.github.andreyasadchy.xtra.util.vod

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    // Mirrors the currently published TwitchRecover host list, with a fixed
    // local fallback so recovery does not depend on a runtime GitHub request.
    private val vodHosts = listOf(
        "https://vod-secure.twitch.tv",
        "https://vod-metro.twitch.tv",
        "https://vod-pop-secure.twitch.tv",
        "https://d2e2de1etea730.cloudfront.net",
        "https://dqrpb9wgowsf5.cloudfront.net",
        "https://ds0h3roq6wcgc.cloudfront.net",
        "https://d2nvs31859zcd8.cloudfront.net",
        "https://d2aba1wr3818hz.cloudfront.net",
        "https://d3c27h4odz752x.cloudfront.net",
        "https://dgeft87wbj63p.cloudfront.net",
        "https://d1m7jfoe9zdc1j.cloudfront.net",
        "https://d3vd9lfkzbru3h.cloudfront.net",
        "https://d2vjef5jvl6bfs.cloudfront.net",
        "https://d1ymi26ma8va5x.cloudfront.net",
        "https://d1mhjrowxxagfy.cloudfront.net",
        "https://ddacn6pr5v0tl.cloudfront.net",
        "https://d3aqoihi2n8ty8.cloudfront.net"
    )

    // TwitchRecover checks these direct quality playlists; "chunked" is a
    // media playlist, not necessarily a master playlist.
    private val candidateQualities = listOf("chunked", "1080p60")

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
     * Searches a bounded range around the recorded UTC start time. Archive
     * timestamps can be slightly imprecise, so test -30 through +60 seconds.
     * Every host/quality candidate is checked concurrently, with a hard deadline.
     */
    suspend fun recover(
        streamerLogin: String,
        streamId: Long,
        startTimestamp: Long,
        timestampIsMinuteOnly: Boolean = false,
        searchTimeoutMillis: Long = 90_000L,
        hostLimit: Int = vodHosts.size,
        qualityLimit: Int = candidateQualities.size
    ): RecoveredVod? = withContext(Dispatchers.IO) {
        val login = streamerLogin.trim().lowercase()
        require(login.matches(Regex("[a-z0-9_]{1,25}"))) { "Enter a valid Twitch channel login." }
        require(streamId > 0) { "Enter a valid stream ID." }
        require(startTimestamp > 0) { "Enter a valid Unix start timestamp." }

        val deadlineNanos = System.nanoTime() + searchTimeoutMillis.coerceAtLeast(1_000L) * 1_000_000L
        val offsets = if (timestampIsMinuteOnly) {
            (0L..59L).toList()
        } else {
            buildList {
                add(0L)
                for (distance in 1L..30L) {
                    add(-distance)
                    add(distance)
                }
                for (second in 31L..60L) add(second)
            }
        }

        for (offset in offsets) {
            coroutineContext.ensureActive()
            if (System.nanoTime() >= deadlineNanos) return@withContext null
            val timestamp = startTimestamp + offset
            val basePath = buildPlaylistPath(login, streamId, timestamp)
                .removeSuffix("/chunked/index-dvr.m3u8")

            val checks = coroutineScope {
                (vodHosts.take(hostLimit.coerceAtLeast(1)).flatMap { host ->
                    candidateQualities.take(qualityLimit.coerceAtLeast(1)).map { quality ->
                        async {
                            val playlistUrl = "$host$basePath/$quality/index-dvr.m3u8"
                            findPlayableQualities(playlistUrl, quality)
                        }
                    }
                }).awaitAll()
            }
            val playable = checks.flatten().distinctBy { it.url }
            if (playable.isNotEmpty()) {
                val selected = playable.first()
                return@withContext RecoveredVod(login, streamId, timestamp, selected.url, playable)
            }
        }
        null
    }

    private suspend fun findPlayableQualities(
        playlistUrl: String,
        qualityName: String
    ): List<RecoveredQuality> {
        coroutineContext.ensureActive()
        val content = fetchPlaylist(playlistUrl) ?: return emptyList()

        // A direct quality URL normally serves a media playlist. Some hosts may
        // instead return a master playlist, so retain support for both formats.
        val parsed = parseQualities(content, playlistUrl)
        val candidates = if (parsed.isNotEmpty()) {
            parsed
        } else if (content.contains("#EXTINF", ignoreCase = true)) {
            val resolution = Regex("""(\d+)p""").find(qualityName)?.groupValues?.get(1)?.toIntOrNull()
            listOf(
                RecoveredQuality(
                    name = qualityName,
                    resolution = resolution,
                    frameRate = if (qualityName.endsWith("60")) 60f else null,
                    bitrate = null,
                    url = playlistUrl
                )
            )
        } else {
            emptyList()
        }

        return candidates.filter { quality ->
            coroutineContext.ensureActive()
            val media = if (quality.url == playlistUrl) content else fetchPlaylist(quality.url)
            media != null && hasReachableSegment(media, quality.url)
        }
    }

    private fun hasReachableSegment(mediaPlaylist: String, playlistUrl: String): Boolean {
        val segment = mediaPlaylist.lineSequence()
            .map(String::trim)
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false
        val segmentUrl = runCatching { URL(URL(playlistUrl), segment).toString() }.getOrNull()
            ?: return false
        return isReachableMedia(segmentUrl)
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
            connectTimeout = 1500
            readTimeout = 1500
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Range", "bytes=0-65535")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader().use { reader ->
                val text = StringBuilder()
                while (text.length < 65536) {
                    val line = reader.readLine() ?: break
                    text.append(line).append('\n')
                }
                text.toString().takeIf { it.startsWith("#EXTM3U") }
            }
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
