package com.github.andreyasadchy.xtra.ui.common

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.helix.user.User
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object SharedChatStreamAvatars {
    fun load(
        fragment: Fragment,
        broadcasterId: String,
        container: LinearLayout,
        isCurrent: () -> Boolean,
    ): Job {
        val context = fragment.requireContext()
        val cache = (context.applicationContext as XtraApp).xtraModule.sharedChatParticipantsCache
        val headers = TwitchApiHelper.getHelixHeaders(context)
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

        return fragment.viewLifecycleOwner.lifecycleScope.launch {
            val users = try {
                cache.getParticipants(broadcasterId, headers, networkLibrary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (isCurrent() && users.size > 1) {
                render(context, container, users)
            } else if (isCurrent()) {
                container.removeAllViews()
                container.visibility = View.GONE
            }
        }
    }

    private fun render(context: Context, container: LinearLayout, users: List<User>) {
        container.removeAllViews()
        val visibleUsers = users.filter { !it.profileImageURL.isNullOrBlank() }.take(MAX_AVATARS)
        if (visibleUsers.size < 2) {
            container.visibility = View.GONE
            return
        }

        visibleUsers.forEachIndexed { index, user ->
            val avatar = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, AVATAR_SIZE), dp(context, AVATAR_SIZE)).apply {
                    if (index > 0) marginStart = -dp(context, AVATAR_OVERLAP)
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = user.displayName ?: user.login
            }
            container.addView(avatar)
            context.imageLoader.enqueue(
                ImageRequest.Builder(context).apply {
                    data(user.profileImageURL)
                    transformations(CircleCropTransformation())
                    crossfade(true)
                    target(avatar)
                }.build()
            )
        }

        val remaining = users.size - visibleUsers.size
        if (remaining > 0) {
            val count = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(context, AVATAR_SIZE)
                ).apply {
                    marginStart = dp(context, 3)
                }
                gravity = android.view.Gravity.CENTER
                text = "+$remaining"
                textSize = 11f
                setTextColor(ContextCompat.getColor(context, android.R.color.secondary_text_light))
                setPadding(dp(context, 2), 0, dp(context, 2), 0)
            }
            container.addView(count)
        }
        container.visibility = View.VISIBLE
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private const val MAX_AVATARS = 3
    private const val AVATAR_SIZE = 22
    private const val AVATAR_OVERLAP = 7
}
