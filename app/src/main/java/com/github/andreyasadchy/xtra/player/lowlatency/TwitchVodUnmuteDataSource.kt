package com.github.andreyasadchy.xtra.player.lowlatency

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Tries Twitch's alternate, unmuted VOD segment when the requested segment is marked muted.
 * If the alternate is unavailable, the original segment is requested normally.
 *
 * This wrapper is installed only for VOD playback, never for live streams.
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
        val alternatePath = MUTED_SEGMENT_SUFFIX.replaceFirst(path, "")
        if (alternatePath == path) {
            return upstream.open(dataSpec)
        }

        val alternateUri = originalUri.buildUpon().encodedPath(alternatePath).build()
        return try {
            upstream.open(dataSpec.withUri(alternateUri))
        } catch (alternateError: IOException) {
            try {
                upstream.close()
            } catch (_: IOException) {
                // The failed alternate request may not have opened a connection.
            }
            try {
                upstream.open(dataSpec)
            } catch (originalError: IOException) {
                originalError.addSuppressed(alternateError)
                throw originalError
            }
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
        // Only rewrite a final "-muted" marker in a media filename, not playlist names.
        val MUTED_SEGMENT_SUFFIX = Regex("-muted(?=\\.[^./]+$|$)")
    }
}
