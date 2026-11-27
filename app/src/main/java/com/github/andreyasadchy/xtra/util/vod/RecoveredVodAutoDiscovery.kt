package com.github.andreyasadchy.xtra.util.vod

import android.content.Context
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

/**
 * Opportunistic deleted-VOD discovery for channel Videos tabs.
 *
 * A channel is checked at most once every eight hours. Automatic checks use a
 * small candidate/host budget so they cannot monopolize the app's network work.
 */
object RecoveredVodAutoDiscovery {
    private const val CHECK_INTERVAL_MILLIS = 8L * 60L * 60L * 1000L
    private const val MAX_CANDIDATES_PER_CHECK = 2
    private const val AUTO_RECOVERY_TIMEOUT_MILLIS = 12_000L
    private const val AUTO_HOST_LIMIT = 4
    private const val AUTO_QUALITY_LIMIT = 1

    /**
     * Returns true when at least one new playable VOD was saved.
     * The check timestamp is recorded before network work, preventing repeated
     * attempts if the user quickly leaves and reopens the channel.
     */
    suspend fun check(context: Context, streamerLogin: String): Boolean =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val login = streamerLogin.trim().removePrefix("@").lowercase(Locale.ROOT)
            if (!login.matches(Regex("[a-z0-9_]{1,25}"))) return@withContext false

            val prefs = appContext.prefs()
            val checkKey = "recovered_vod_auto_check_$login"
            val now = System.currentTimeMillis()
            val lastCheck = prefs.getLong(checkKey, 0L)
            if (lastCheck > 0L && now - lastCheck < CHECK_INTERVAL_MILLIS) {
                return@withContext false
            }

            // Commit immediately so repeated tab openings do not start duplicate scans.
            prefs.edit().putLong(checkKey, now).apply()

            val candidates = runCatching {
                VodStreamDiscoveryRepository.search(appContext, login)
            }.getOrNull().orEmpty()
            if (candidates.isEmpty()) return@withContext false

            val knownIds = RecoveredVodStore.load(appContext, login)
                .mapTo(HashSet()) { it.streamId }
            for (candidate in candidates.asSequence()
                .filterNot { it.streamId in knownIds }
                .take(MAX_CANDIDATES_PER_CHECK)
            ) {
                val recovered = runCatching {
                    VodRecoveryRepository.recover(
                        streamerLogin = candidate.streamerLogin,
                        streamId = candidate.streamId,
                        startTimestamp = candidate.startTimestamp,
                        searchTimeoutMillis = AUTO_RECOVERY_TIMEOUT_MILLIS,
                        hostLimit = AUTO_HOST_LIMIT,
                        qualityLimit = AUTO_QUALITY_LIMIT
                    )
                }.getOrNull() ?: continue

                val qualities = recovered.qualities.distinctBy { quality ->
                    listOf(quality.name, quality.resolution, quality.frameRate, quality.bitrate)
                }
                if (qualities.isEmpty()) continue

                RecoveredVodStore.save(
                    appContext,
                    RecoveredVodStore.Entry(
                        streamerLogin = recovered.streamerLogin,
                        streamId = recovered.streamId,
                        startTimestamp = recovered.startTimestamp,
                        title = candidate.title,
                        durationSeconds = candidate.durationSeconds,
                        qualities = qualities
                    )
                )
                return@withContext true
            }
            false
        }
}
