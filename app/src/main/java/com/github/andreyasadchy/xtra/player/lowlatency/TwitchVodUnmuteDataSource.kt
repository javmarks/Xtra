package com.github.andreyasadchy.xtra.player.lowlatency

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Tries Twitch's alternate, unmuted MPEG-TS VOD segment when the requested
 * segment is marked muted. If it is unavailable at the selected rendition,
 * tries the same original segment at lower MPEG-TS renditions before falling
 * back to the originally requested (muted) segment.
 *
 * Fragmented-MP4 segments and their initialization maps are deliberately left
 * untouched because mixing renditions can break their shared initialization
 * data. This wrapper is installed only for VOD playback, never for live streams.
 */
@UnstableApi
class TwitchVodUnmuteDataSource(
    private val upstream: DataSource
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        val originalUri = dataSpec.uri
        val path = originalUri.encodedPath ?: return upstream.open(dataSpec)
        if (!path.endsWith(".ts", ignoreCase = true)) {
            return upstream.open(dataSpec)
        }

        val alternatePath = when {
            UNMUTED_SEGMENT_SUFFIX.containsMatchIn(path) ->
                UNMUTED_SEGMENT_SUFFIX.replaceFirst(path, "-muted.ts")
            MUTED_SEGMENT_SUFFIX.containsMatchIn(path) ->
                MUTED_SEGMENT_SUFFIX.replaceFirst(path, "")
            else -> return upstream.open(dataSpec)
        }

        val candidates = mutableListOf<Uri>()
        candidates += originalUri.buildUpon().encodedPath(alternatePath).build()

        val pathParts = path.split("/")
        val qualityIndex = pathParts.indexOfLast { it in MPEG_TS_QUALITIES }
        if (qualityIndex >= 0) {
            for (quality in MPEG_TS_QUALITIES.drop(qualityIndex + 1)) {
                val lowerParts = pathParts.toMutableList()
                lowerParts[qualityIndex] = quality
                val lowerPath = lowerParts.joinToString("/")
                val lowerAlternatePath = when {
                    UNMUTED_SEGMENT_SUFFIX.containsMatchIn(lowerPath) ->
                        UNMUTED_SEGMENT_SUFFIX.replaceFirst(lowerPath, "-muted.ts")
                    MUTED_SEGMENT_SUFFIX.containsMatchIn(lowerPath) ->
                        MUTED_SEGMENT_SUFFIX.replaceFirst(lowerPath, "")
                    else -> lowerPath
                }
                candidates += originalUri.buildUpon().encodedPath(lowerAlternatePath).build()
            }
        }

        var lastAlternateError: IOException? = null
        for (candidateUri in candidates.distinct()) {
            try {
                return upstream.open(dataSpec.withUri(candidateUri))
            } catch (e: IOException) {
                lastAlternateError = e
                try {
                    upstream.close()
                } catch (_: IOException) {
                    // Reset the failed request before trying another rendition.
                }
            }
        }

        return try {
            upstream.open(dataSpec)
        } catch (originalError: IOException) {
            lastAlternateError?.let(originalError::addSuppressed)
            throw originalError
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return upstream.read(buffer, offset, length)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    @Throws(IOException::class)
    override fun close() {
        upstream.close()
    }

    @UnstableApi
    class Factory(
        private val upstreamFactory: DataSource.Factory
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return TwitchVodUnmuteDataSource(upstreamFactory.createDataSource())
        }
    }

    private companion object {
        // Twitch VOD renditions, ordered from highest to lowest.
        val MPEG_TS_QUALITIES = listOf(
            "chunked",
            "1080p60",
            "1080p30",
            "720p60",
            "720p30",
            "480p30",
            "360p30",
            "160p30"
        )
        val UNMUTED_SEGMENT_SUFFIX = Regex("-unmuted(?=\\.ts$)")
        val MUTED_SEGMENT_SUFFIX = Regex("-muted(?=\\.ts$)")
    }
}
