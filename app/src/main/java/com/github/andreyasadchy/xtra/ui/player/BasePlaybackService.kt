package com.github.andreyasadchy.xtra.ui.player

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.github.andreyasadchy.xtra.XtraModule
import com.github.andreyasadchy.xtra.model.PlaybackState
import com.github.andreyasadchy.xtra.model.VideoQuality
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.math.floor

abstract class BasePlaybackService : LifecycleService() {

    lateinit var xtraModule: XtraModule

    val integrity = MutableSharedFlow<String?>()

    var type: String? = null
    var streamId: String? = null
    var videoId: String? = null
    var clipId: String? = null
    var offlineVideoId: Int? = null
    var channelId: String? = null
    var channelLogin: String? = null
    var channelName: String? = null
    var channelImage: String? = null
    var gameId: String? = null
    var gameSlug: String? = null
    var gameName: String? = null
    var title: String? = null
    var thumbnail: String? = null
    var createdAt: String? = null
    var viewerCount: Int? = null
    var durationSeconds: Int? = null
    var videoType: String? = null
    var videoOffsetSeconds: Int? = null
    var videoCreatedAt: String? = null
    var videoAnimatedPreviewURL: String? = null
    var savedPosition: Long? = null
    var paused = false
    var qualities: List<VideoQuality>? = null
    var quality: VideoQuality? = null
    var previousQuality: VideoQuality? = null
    var restoreQuality = false
    var playlistUrl: String? = null
    var restorePlaylist = false
    var useCustomProxy = false
    var currentCustomProxy = 0
    var useStreamProxy = false
    var currentStreamProxy = 0
    var skipAccessToken = false

    var chatUrl: String? = null
    var started = false
    var loaded = false

    private var dropsWatchJob: Job? = null

    protected fun updateDropsWatchTracking(isPlaying: Boolean) {
        val canTrack = isPlaying
                && type == STREAM
                && !streamId.isNullOrBlank()
                && !channelId.isNullOrBlank()
                && !channelLogin.isNullOrBlank()
                && !tokenPrefs().getString(C.USER_ID, null).isNullOrBlank()
        if (!canTrack) {
            dropsWatchJob?.cancel()
            dropsWatchJob = null
            return
        }
        if (dropsWatchJob?.isActive == true) return
        dropsWatchJob = lifecycleScope.launch {
            while (isActive) {
                delay(59_000)
                if (!isActive) break
                val currentStreamId = streamId
                val currentChannelId = channelId
                val currentChannelLogin = channelLogin
                val currentUserId = tokenPrefs().getString(C.USER_ID, null)
                if (type != STREAM || currentStreamId.isNullOrBlank()
                    || currentChannelId.isNullOrBlank() || currentChannelLogin.isNullOrBlank()
                    || currentUserId.isNullOrBlank()) {
                    break
                }
                try {
                    xtraModule.playerRepository.sendMinuteWatched(
                        networkLibrary = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        userId = currentUserId,
                        streamId = currentStreamId,
                        channelId = currentChannelId,
                        channelLogin = currentChannelLogin,
                        gameId = gameId,
                        gameName = gameName,
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(this@BasePlaybackService, includeToken = true)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Drops tracking is best-effort and must never interrupt playback.
                }
            }
        }
    }

    override fun onDestroy() {
        dropsWatchJob?.cancel()
        dropsWatchJob = null
        super.onDestroy()
    }

    protected suspend fun restorePlaybackState() {
        val savedState = xtraModule.playerRepository.getPlaybackStates().firstOrNull()
        xtraModule.playerRepository.deletePlaybackStates()
        if (savedState != null) {
            type = savedState.type
            streamId = savedState.streamId
            videoId = savedState.videoId
            clipId = savedState.clipId
            offlineVideoId = savedState.offlineVideoId
            channelId = savedState.channelId
            channelLogin = savedState.channelLogin
            channelName = savedState.channelName
            channelImage = savedState.channelImage
            gameId = savedState.gameId
            gameSlug = savedState.gameSlug
            gameName = savedState.gameName
            title = savedState.title
            thumbnail = savedState.thumbnail
            createdAt = savedState.createdAt
            viewerCount = savedState.viewerCount
            durationSeconds = savedState.durationSeconds
            videoType = savedState.videoType
            videoOffsetSeconds = savedState.videoOffsetSeconds
            videoCreatedAt = savedState.videoCreatedAt
            videoAnimatedPreviewURL = savedState.videoAnimatedPreviewURL
            savedPosition = savedState.position
            paused = savedState.paused
            qualities = savedState.qualities?.let { qualities ->
                xtraModule.json.decodeFromString<JsonArray>(qualities).map {
                    xtraModule.json.decodeFromJsonElement<VideoQuality>(it)
                }
            }
            quality = savedState.quality?.let { xtraModule.json.decodeFromString(it) }
            previousQuality = savedState.previousQuality?.let { xtraModule.json.decodeFromString(it) }
            restoreQuality = savedState.restoreQuality
            playlistUrl = savedState.playlistUrl
            restorePlaylist = savedState.restorePlaylist
            useCustomProxy = savedState.useCustomProxy
            currentCustomProxy = savedState.currentCustomProxy
            useStreamProxy = savedState.useStreamProxy
            currentStreamProxy = savedState.currentStreamProxy
            skipAccessToken = savedState.skipAccessToken
        }
    }

    protected suspend fun savePlaybackState(position: Long?, paused: Boolean) {
        val item = PlaybackState(
            type = type,
            streamId = streamId,
            videoId = videoId,
            clipId = clipId,
            offlineVideoId = offlineVideoId,
            channelId = channelId,
            channelLogin = channelLogin,
            channelName = channelName,
            channelImage = channelImage,
            gameId = gameId,
            gameSlug = gameSlug,
            gameName = gameName,
            title = title,
            thumbnail = thumbnail,
            createdAt = createdAt,
            viewerCount = viewerCount,
            durationSeconds = durationSeconds,
            videoType = videoType,
            videoOffsetSeconds = videoOffsetSeconds,
            videoCreatedAt = videoCreatedAt,
            videoAnimatedPreviewURL = videoAnimatedPreviewURL,
            position = position,
            paused = paused,
            qualities = qualities?.let { qualities ->
                buildJsonArray {
                    qualities.forEach {
                        add(xtraModule.json.encodeToJsonElement(it))
                    }
                }.toString()
            },
            quality = quality?.let { xtraModule.json.encodeToString(it) },
            previousQuality = previousQuality?.let { xtraModule.json.encodeToString(it) },
            restoreQuality = restoreQuality,
            playlistUrl = playlistUrl,
            restorePlaylist = restorePlaylist,
            useCustomProxy = useCustomProxy,
            currentCustomProxy = currentCustomProxy,
            useStreamProxy = useStreamProxy,
            currentStreamProxy = currentStreamProxy,
            skipAccessToken = skipAccessToken,
        )
        xtraModule.playerRepository.savePlaybackStates(listOf(item))
    }

    protected fun setDefaultQuality() {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCapabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        val cellular = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val defaultQuality = if (cellular) {
            prefs().getString(C.PLAYER_DEFAULT_CELLULAR_QUALITY, "saved")
        } else {
            prefs().getString(C.PLAYER_DEFAULT_QUALITY, "saved")
        }?.substringBefore(" ")
        quality = when (defaultQuality) {
            "saved" -> {
                val savedQuality = prefs().getString(C.PLAYER_QUALITY, "720p60")?.substringBefore(" ")
                when (savedQuality) {
                    VideoQuality.AUTO_QUALITY -> qualities?.find { it.name == VideoQuality.AUTO_QUALITY }
                    VideoQuality.AUDIO_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
                    VideoQuality.CHAT_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.CHAT_ONLY_QUALITY }
                    else -> findQuality(savedQuality)
                }
            }
            VideoQuality.AUTO_QUALITY -> qualities?.find { it.name == VideoQuality.AUTO_QUALITY }
            "Source" -> qualities?.find { it.name != VideoQuality.AUTO_QUALITY }
            VideoQuality.AUDIO_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
            VideoQuality.CHAT_ONLY_QUALITY -> qualities?.find { it.name == VideoQuality.CHAT_ONLY_QUALITY }
            else -> findQuality(defaultQuality)
        } ?: qualities?.firstOrNull()
    }

    private fun findQuality(targetQualityString: String?): VideoQuality? {
        val targetQuality = targetQualityString?.split("p")
        return targetQuality?.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull()?.let { targetResolution ->
            val targetFps = targetQuality.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 30
            val last = qualities?.lastOrNull { it.name != VideoQuality.AUDIO_ONLY_QUALITY && it.name != VideoQuality.CHAT_ONLY_QUALITY }
            qualities?.find { quality ->
                quality.resolution != null
                        && ((targetResolution == quality.resolution
                        && targetFps >= (quality.frameRate?.let { fps -> floor(fps) } ?: 30f))
                        || targetResolution > quality.resolution
                        || quality == last)
            }
        }
    }

    companion object {
        const val STREAM = "stream"
        const val VIDEO = "video"
        const val CLIP = "clip"
        const val OFFLINE_VIDEO = "offlineVideo"
    }
}