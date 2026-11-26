package com.github.andreyasadchy.xtra.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Builds candidate Twitch DVR playlist URLs for a stream whose VOD is no longer
 * listed by Twitch. This class only constructs candidates; callers must verify
 * both the playlist and its media segments before offering playback/download.
 */
object DeletedVodRecovery {

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
    )

    /**
     * Returns candidate DVR playlists for a Twitch stream ID and its UTC start
     * timestamp (Unix seconds). This is not the numeric ID from /videos/{id}.
     */
    fun candidatePlaylistUrls(
        channelLogin: String,
        streamId: String,
        startTimestampSeconds: Long,
    ): List<String> {
        val login = channelLogin.trim().lowercase()
        if (!login.matches(Regex("[a-z0-9_]{1,25}"))) return emptyList()
        if (!streamId.matches(Regex("[0-9]{5,20}"))) return emptyList()
        if (startTimestampSeconds <= 0L) return emptyList()

        val base = "${login}_${streamId}_${startTimestampSeconds}"
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(base.toByteArray(StandardCharsets.UTF_8))
        val hash = digest.joinToString("") { byte -> "%02x".format(byte) }.take(20)
        val path = "/${hash}_${base}/chunked/index-dvr.m3u8"

        return vodHosts.map { host -> host + path }
    }

    /**
     * Produces the bounded set of possible UTC start seconds when a source only
     * provides minute precision. Exact-second lookup should always run first.
     */
    fun minuteTimestampCandidates(startTimestampAtMinute: Long): LongRange {
        val minute = startTimestampAtMinute - Math.floorMod(startTimestampAtMinute, 60L)
        return minute..(minute + 59L)
    }
}
