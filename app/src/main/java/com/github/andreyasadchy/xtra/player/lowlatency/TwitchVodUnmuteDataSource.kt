package com.github.andreyasadchy.xtra.player.lowlatency

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Tries Twitch's alternate, unmuted MPEG-TS VOD segment when the requested
 * segment is marked muted. If it is definitively unavailable (HTTP 403, 404,
 * or 410) at the selected rendition, tries lower MPEG-TS renditions. Transient
 * network errors stop retries immediately so they do not multiply playback
 * and seek delays.
 *
 * Fragmented-MP4 segments and their initialization maps are deliberately left
 * untouched because mixing renditions can break their shared initialization
 * data. This wrapper is installed only for VOD playback, never for live streams.
 */
@UnstableApi
class TwitchVodUnmuteDataSource(
    private val upstream: DataSource
) : DataSource {

    private var activeSegmentName: String = "unknown"
    private var readFailureRecorded = false

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
        val qualityIndex = pathParts.indexOfLast {
            it == "chunked" || QUALITY_SEGMENT_PATTERN.matches(it)
        }
        if (qualityIndex >= 0) {
            val currentQuality = pathParts[qualityIndex]
            val lowerQualities = if (currentQuality == "chunked") {
                MPEG_TS_QUALITY_LEVELS
            } else {
                val currentMatch = QUALITY_SEGMENT_PATTERN.matchEntire(currentQuality)
                if (currentMatch != null) {
                    val currentHeight = currentMatch.groupValues[1].toInt()
                    val currentFrameRate = currentMatch.groupValues[2].toInt()
                    val codecSuffix = currentMatch.groupValues[3]
                    MPEG_TS_QUALITY_LEVELS.filter { quality ->
                        val qualityMatch = QUALITY_SEGMENT_PATTERN.matchEntire(quality)!!
                        val height = qualityMatch.groupValues[1].toInt()
                        val frameRate = qualityMatch.groupValues[2].toInt()
                        height < currentHeight || height == currentHeight && frameRate < currentFrameRate
                    }.map { "$it$codecSuffix" }
                } else {
                    emptyList()
                }
            }

            for (quality in lowerQualities) {
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
            val candidateName = safeSegmentName(candidateUri)
            val candidateLabel = safeCandidateLabel(candidateUri)
            activeSegmentName = candidateName
            readFailureRecorded = false
            recordDiagnostic("OPEN TRY " + candidateLabel)
            try {
                val result = upstream.open(dataSpec.withUri(candidateUri))
                recordDiagnostic("OPEN OK " + candidateLabel + " length=" + result)
                return result
            } catch (e: IOException) {
                lastAlternateError = e
                val responseCode = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                recordDiagnostic("OPEN FAIL " + candidateLabel + (responseCode?.let { " HTTP=" + it } ?: "") + " " + e.javaClass.simpleName)
                try {
                    upstream.close()
                } catch (_: IOException) {
                    // Reset the failed request before trying another rendition.
                }
                // Only try another quality when Twitch explicitly says this
                // alternate segment is unavailable. Do not multiply timeouts,
                // connection failures, or other transient network errors.
                if (e !is HttpDataSource.InvalidResponseCodeException ||
                    e.responseCode !in UNAVAILABLE_SEGMENT_HTTP_CODES
                ) {
                    break
                }
            }
        }

        val originalName = safeSegmentName(originalUri)
        activeSegmentName = originalName
        readFailureRecorded = false
        val originalLabel = safeCandidateLabel(originalUri)
        recordDiagnostic("FALLBACK ORIGINAL " + originalLabel)
        return try {
            val result = upstream.open(dataSpec)
            recordDiagnostic("OPEN OK ORIGINAL " + originalLabel + " length=" + result)
            result
        } catch (originalError: IOException) {
            val responseCode = (originalError as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            recordDiagnostic("OPEN FAIL ORIGINAL " + originalLabel + (responseCode?.let { " HTTP=" + it } ?: "") + " " + originalError.javaClass.simpleName)
            lastAlternateError?.let(originalError::addSuppressed)
            throw originalError
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return try {
            upstream.read(buffer, offset, length)
        } catch (e: IOException) {
            if (!readFailureRecorded) {
                readFailureRecorded = true
                recordDiagnostic("READ FAIL " + activeSegmentName + " " + e.javaClass.simpleName)
            }
            throw e
        }
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

    companion object {
        private const val MAX_DIAGNOSTIC_EVENTS = 60
        private val diagnosticEvents = java.util.ArrayDeque<String>()

        @JvmStatic
        fun getRecentDiagnostics(): List<String> = synchronized(diagnosticEvents) {
            diagnosticEvents.toList()
        }

        private fun recordDiagnostic(message: String) {
            synchronized(diagnosticEvents) {
                diagnosticEvents.addLast(System.currentTimeMillis().toString() + " " + message)
                while (diagnosticEvents.size > MAX_DIAGNOSTIC_EVENTS) {
                    diagnosticEvents.removeFirst()
                }
            }
        }

        private fun safeSegmentName(uri: Uri): String =
            uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "unknown"

        // Record only the rendition folder and segment filename. Never include
        // the host, query string, signed tokens, or other URL credentials.
        private fun safeCandidateLabel(uri: Uri): String {
            val parts = (uri.encodedPath ?: "").split("/")
            val quality = parts.lastOrNull {
                it == "chunked" || QUALITY_SEGMENT_PATTERN.matches(it)
            }
            return if (quality != null) quality + "/" + safeSegmentName(uri)
            else safeSegmentName(uri)
        }

        // Twitch VOD rendition names include legacy labels and newer
        // resolution/frame-rate labels that may carry a codec suffix (e.g. -av01).
        // Keep this list ordered from highest to lowest.
        val MPEG_TS_QUALITY_LEVELS = listOf(
            "1440p60",
            "1440p30",
            "1080p60",
            "1080p30",
            "936p60",
            "936p30",
            "720p60",
            "720p30",
            "480p30",
            "360p30",
            "160p30"
        )
        val QUALITY_SEGMENT_PATTERN = Regex("^(\\d{3,4})p(\\d{2,3})(-[a-zA-Z0-9]+)?$")
        val UNAVAILABLE_SEGMENT_HTTP_CODES = setOf(403, 404, 410)
        val UNMUTED_SEGMENT_SUFFIX = Regex("-unmuted(?=\\.ts$)")
        val MUTED_SEGMENT_SUFFIX = Regex("-muted(?=\\.ts$)")
    }
}
