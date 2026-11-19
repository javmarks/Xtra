package com.github.andreyasadchy.xtra.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentChatBinding
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.github.andreyasadchy.xtra.ui.chat.ChatViewModel.Companion.ChatViewModelFactory
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.view.AutoCompleteAdapter
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.reduceDragSensitivity
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.tabs.TabLayoutMediator
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class ChatFragment : BaseNetworkFragment(), MessageClickedDialog.OnButtonClickListener, ReplyClickedDialog.OnButtonClickListener {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels { ChatViewModelFactory }
    private var adapter: ChatAdapter? = null

    val mergedChatViewModel: ChatViewModel
        get() = viewModel

    val mergedChatAdapter: ChatAdapter?
        get() = adapter

    val mergedChatChannelId: String?
        get() = arguments?.getString(KEY_CHANNEL_ID)

    val isMergedRendererOnly: Boolean
        get() = arguments?.getBoolean(KEY_RENDERER_ONLY) == true

    // The player owns the real layout orientation. The chat pane itself is narrow in
    // landscape, so its dimensions/configuration cannot reliably identify player mode.
    fun applyPlayerLandscapeMode(isLandscape: Boolean) {
        if (!isAdded || _binding == null) return
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val buttons = listOf(binding.dropsButton, binding.chatIdentityButton, binding.moderationLogsButton)
        if (isLandscape) {
            buttons.forEach { button ->
                button.minHeight = dp(24)
                button.minimumHeight = dp(24)
                button.minWidth = 0
                button.setPadding(dp(4), 0, dp(4), 0)
                button.textSize = 9f
                button.layoutParams = button.layoutParams.apply { height = dp(26) }
            }
            binding.dropsButton.text = "Drops"
            binding.chatIdentityButton.text = "ID"
            binding.moderationLogsButton.text = "Logs"
            binding.moderationLogsBar.setPadding(dp(2), 0, dp(2), 0)
            binding.pinnedChatBar.orientation = android.widget.LinearLayout.HORIZONTAL
            binding.pinnedChatBar.setPadding(dp(6), dp(3), dp(6), dp(3))
            (binding.pinnedChatBar.getChildAt(0).layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                it.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                binding.pinnedChatBar.getChildAt(0).layoutParams = it
            }
            binding.pinnedChatText.layoutParams = binding.pinnedChatText.layoutParams.apply {
                width = 0
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                if (this is android.widget.LinearLayout.LayoutParams) {
                    weight = 1f
                    topMargin = 0
                    marginStart = dp(6)
                }
            }
            binding.pinnedChatText.maxLines = 1
            binding.pinnedChatText.textSize = 11f
            binding.pinnedChatBy.isGone = true
            binding.pinnedChatExpiry.isGone = true
        } else {
            buttons.forEach { button ->
                button.minHeight = dp(36)
                button.minimumHeight = dp(36)
                button.minWidth = 0
                button.textSize = 14f
                button.layoutParams = button.layoutParams.apply {
                    height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            binding.dropsButton.text = "Drops"
            binding.chatIdentityButton.text = "Chat Identity"
            binding.moderationLogsButton.text = "Mod Logs"
            binding.moderationLogsBar.setPadding(dp(6), dp(2), dp(6), dp(2))
            binding.pinnedChatBar.orientation = android.widget.LinearLayout.VERTICAL
            binding.pinnedChatBar.setPadding(dp(12), dp(8), dp(12), dp(8))
            val header = binding.pinnedChatBar.getChildAt(0)
            (header.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let {
                it.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                header.layoutParams = it
            }
            binding.pinnedChatText.layoutParams = binding.pinnedChatText.layoutParams.apply {
                width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                if (this is android.widget.LinearLayout.LayoutParams) {
                    weight = 0f
                    topMargin = dp(3)
                    marginStart = 0
                }
            }
            binding.pinnedChatText.maxLines = 3
            binding.pinnedChatText.textSize = 14f
            binding.pinnedChatBy.isVisible = true
            binding.pinnedChatExpiry.isVisible = true
        }
    }

    fun showPinnedMessageDetailsFromAllChats() {
        if (!isAdded || _binding == null) return
        binding.pinnedChatBar.performClick()
    }

    // All Chats opens channel-specific controls through the owning source fragment,
    // so reward redemptions and streak shares always use the correct broadcaster.
    fun showChannelPointsFromAllChats() {
        if (!isAdded || _binding == null) return
        val wasVisible = binding.channelPointsBar.isVisible
        binding.channelPointsBar.isVisible = true
        binding.channelPointsBar.performClick()
        binding.channelPointsBar.isVisible = wasVisible
    }

    fun showWatchStreakFromAllChats() {
        if (!isAdded || _binding == null || viewModel.watchStreak.value == null) return
        val wasVisible = binding.watchStreakIndicator.isVisible
        binding.watchStreakIndicator.isVisible = true
        binding.watchStreakIndicator.performClick()
        binding.watchStreakIndicator.isVisible = wasVisible
    }

    // Keep following state independent of the finger's current touch state. A reader
    // who scrolls upward must not be pulled back to the newest message when a new row arrives.
    private var isFollowingChat = true
    private var showChatStatus = false
    private var hasRecentEmotes = false
    private var messagingEnabled = false

    private var autoCompleteAdapter: AutoCompleteAdapter<Any>? = null

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            toggleEmoteMenu(false)
        }
    }

    private val messageDialog: MessageClickedDialog?
        get() = childFragmentManager.findFragmentByTag("messageDialog") as? MessageClickedDialog

    private val replyDialog: ReplyClickedDialog?
        get() = childFragmentManager.findFragmentByTag("replyDialog") as? ReplyClickedDialog

    private var languageIdentifier: LanguageIdentifier? = null
    private val translators = mutableMapOf<String, Translator>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The player controls orientation; the chat pane itself remains narrow in landscape.
        view.post {
            if (isAdded && _binding != null) {
                (parentFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment)
                    ?.let { applyPlayerLandscapeMode(it.isLandscapePlayerMode()) }
            }
        }
        val isLiveChannel = requireArguments().getBoolean(KEY_IS_LIVE)
        binding.moderationLogsBar.isVisible = isLiveChannel && !isMergedRendererOnly
        binding.topGiftersBar.isVisible = isLiveChannel && !isMergedRendererOnly && viewModel.topGifters.value.isNotEmpty()
        binding.topGiftersBar.setOnClickListener {
            val gifters = viewModel.topGifters.value
            if (gifters.isNotEmpty()) {
                val entries = gifters.mapIndexed { index, gifter ->
                    "${index + 1}. ${gifter.displayName} · ${NumberFormat.getIntegerInstance().format(gifter.giftCount)} gifts"
                }
                requireContext().getAlertDialogBuilder()
                    .setTitle("Top Gifters · This stream")
                    .setItems(entries.toTypedArray(), null)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.topGifters.collectLatest { gifters ->
                    binding.topGiftersBar.isVisible = isLiveChannel && !isMergedRendererOnly && gifters.isNotEmpty()
                    val avatars = listOf(binding.topGifterAvatar1, binding.topGifterAvatar2, binding.topGifterAvatar3)
                    val names = listOf(binding.topGifterName1, binding.topGifterName2, binding.topGifterName3)
                    val counts = listOf(binding.topGifterCount1, binding.topGifterCount2, binding.topGifterCount3)
                    val places = listOf(binding.topGifterPlace1, binding.topGifterPlace2, binding.topGifterPlace3)
                    for (index in 0 until 3) {
                        val gifter = gifters.getOrNull(index)
                        places[index].isVisible = gifter != null
                        names[index].text = gifter?.displayName.orEmpty()
                        counts[index].text = gifter?.let {
                            "${NumberFormat.getIntegerInstance().format(it.giftCount)} gifts"
                        }.orEmpty()
                        avatars[index].isVisible = gifter != null
                        if (gifter?.profileImageUrl.isNullOrBlank()) {
                            avatars[index].setImageDrawable(null)
                        } else {
                            requireContext().imageLoader.enqueue(
                                ImageRequest.Builder(requireContext()).apply {
                                    data(gifter?.profileImageUrl)
                                    transformations(CircleCropTransformation())
                                    crossfade(true)
                                    target(avatars[index])
                                }.build()
                            )
                        }
                        names[index].isVisible = gifter != null
                        counts[index].isVisible = gifter != null
                    }
                }
            }
        }
        binding.dropsButton.setOnClickListener {
            showDropsProgress()
        }
        val identityAccountLogin = requireContext().tokenPrefs().getString(C.USERNAME, null)
        val identityIsLoggedIn = !identityAccountLogin.isNullOrBlank() &&
            (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
        binding.chatIdentityButton.isVisible =
            requireArguments().getBoolean(KEY_IS_LIVE) && !isMergedRendererOnly && identityIsLoggedIn
        binding.chatIdentityButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val identityChannelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN).orEmpty()
                val networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val headers = TwitchApiHelper.getGQLHeaders(requireContext(), true)
                val application = requireContext().applicationContext as XtraApp
                val globalResponse = runCatching {
                    application.xtraModule.graphQLRepository.loadAvailableChatBadges(
                        networkLibrary, headers, identityAccountLogin.orEmpty(), false
                    )
                }.getOrNull()
                val channelResponse = if (identityChannelLogin.isNotBlank()) runCatching {
                    application.xtraModule.graphQLRepository.loadAvailableChatBadges(
                        networkLibrary, headers, identityChannelLogin, true
                    )
                }.getOrNull() else null
                fun parseAvailableBadges(response: String?, channelSpecific: Boolean): List<com.github.andreyasadchy.xtra.model.chat.TwitchBadge> {
                    val root = response?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
                    val user = root.optJSONObject("data")?.optJSONObject("user") ?: return emptyList()
                    val badges = if (channelSpecific) {
                        user.optJSONObject("self")?.optJSONArray("availableBadges")
                    } else user.optJSONArray("availableBadges")
                    return buildList {
                        if (badges != null) for (index in 0 until badges.length()) {
                            val badge = badges.optJSONObject(index) ?: continue
                            val setId = badge.optString("setID").takeIf { it.isNotBlank() && it != "null" } ?: continue
                            val version = badge.optString("version").takeIf { it.isNotBlank() && it != "null" } ?: continue
                            val image = badge.optString("imageURL").takeIf { it.startsWith("https://", true) }
                            add(com.github.andreyasadchy.xtra.model.chat.TwitchBadge(
                                setId = setId, version = version,
                                url1x = image, url2x = image, url3x = image, url4x = image,
                                title = badge.optString("title").takeIf { it.isNotBlank() && it != "null" }
                            ))
                        }
                    }
                }
                if (!isAdded) return@launch
                val selectedBadges = viewModel.userChatBadges.value.orEmpty()
                val globalCatalog = parseAvailableBadges(globalResponse, false)
                val channelCatalog = parseAvailableBadges(channelResponse, true)
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (20 * resources.displayMetrics.density).roundToInt(),
                    (8 * resources.displayMetrics.density).roundToInt(),
                    (20 * resources.displayMetrics.density).roundToInt(),
                    (8 * resources.displayMetrics.density).roundToInt()
                )
            }
                val identityChannelId = requireArguments().getString(KEY_CHANNEL_ID).orEmpty()
            fun addBadgeSection(title: String, catalog: List<com.github.andreyasadchy.xtra.model.chat.TwitchBadge>, channelSpecific: Boolean) {
                if (catalog.isEmpty()) return
                content.addView(TextView(requireContext()).apply {
                    text = title
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, (10 * resources.displayMetrics.density).roundToInt(), 0, (4 * resources.displayMetrics.density).roundToInt())
                })
                catalog.distinctBy { it.setId to it.version }.forEach { badge ->
                    val selected = selectedBadges.any { it.setId == badge.setId && it.version == badge.version }
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, (5 * resources.displayMetrics.density).roundToInt(), 0, (5 * resources.displayMetrics.density).roundToInt())
                    }
                    val badgeImage = android.widget.ImageView(requireContext()).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (36 * resources.displayMetrics.density).roundToInt(),
                            (36 * resources.displayMetrics.density).roundToInt()
                        )
                        contentDescription = badge.title ?: badge.setId
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    val badgeImageUrl = badge.url2x ?: badge.url1x ?: badge.url3x ?: badge.url4x
                    if (!badgeImageUrl.isNullOrBlank()) {
                        requireContext().imageLoader.enqueue(
                            ImageRequest.Builder(requireContext()).apply {
                                data(badgeImageUrl)
                                crossfade(true)
                                target(badgeImage)
                            }.build()
                        )
                    }
                    row.addView(badgeImage)
                    val label = TextView(requireContext()).apply {
                        text = (badge.title?.takeIf { it.isNotBlank() } ?: badge.setId) +
                            if (selected) " · Selected" else ""
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setPadding((12 * resources.displayMetrics.density).roundToInt(), 0, 0, 0)
                    }
                    row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    content.addView(row)
                    val badgeInfoUri = badge.clickUrl?.let { rawUrl ->
                        runCatching {
                            Uri.parse(rawUrl).takeIf {
                                badge.clickAction == "visit_url" &&
                                    it.scheme.equals("https", ignoreCase = true) &&
                                    !it.host.isNullOrBlank()
                            }
                        }.getOrNull()
                    }
                    row.isClickable = true
                    row.setOnClickListener {
                        if (channelSpecific && identityChannelId.isBlank()) {
                            Toast.makeText(requireContext(), "Twitch did not provide this channel ID.", Toast.LENGTH_LONG).show()
                            return@setOnClickListener
                        }
                        row.isEnabled = false
                        viewLifecycleOwner.lifecycleScope.launch {
                            val result = runCatching {
                                if (channelSpecific) {
                                    application.xtraModule.graphQLRepository.selectChannelChatBadge(networkLibrary, headers, identityChannelId, badge.setId, badge.version)
                                } else {
                                    application.xtraModule.graphQLRepository.selectGlobalChatBadge(networkLibrary, headers, badge.setId, badge.version)
                                }
                            }
                            if (!isAdded) return@launch
                            val root = result.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
                            val data = root?.optJSONObject("data")
                            val errors = root?.optJSONArray("errors")
                            val mutationName = if (channelSpecific) "selectChannelBadge" else "selectGlobalBadge"
                            val success = data?.optJSONObject(mutationName) != null && (errors == null || errors.length() == 0)
                            if (success) {
                                val current = viewModel.userChatBadges.value.orEmpty().toMutableList()
                                current.removeAll { it.setId == badge.setId }
                                current.add(com.github.andreyasadchy.xtra.model.chat.Badge(badge.setId, badge.version))
                                viewModel.userChatBadges.value = current
                                label.text = (badge.title?.takeIf { it.isNotBlank() } ?: badge.setId) + " - Selected"
                                Toast.makeText(requireContext(), if (channelSpecific) "Badge selected for this channel." else "Global badge selected.", Toast.LENGTH_SHORT).show()
                            } else {
                                val message = errors?.optJSONObject(0)?.optString("message").orEmpty()
                                Toast.makeText(requireContext(), "Twitch could not change this badge. " + message.take(120), Toast.LENGTH_LONG).show()
                            }
                            row.isEnabled = true
                        }
                    }
                    if (badgeInfoUri != null) {
                        row.setOnLongClickListener {
                            runCatching { startActivity(Intent(Intent.ACTION_VIEW, badgeInfoUri)) }
                            true
                        }
                    }
                    badge.description?.takeIf { it.isNotBlank() }?.let { description ->
                        content.addView(TextView(requireContext()).apply {
                            text = description
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding((48 * resources.displayMetrics.density).roundToInt(), 0, 0, (4 * resources.displayMetrics.density).roundToInt())
                        })
                    }
                }
            }
            addBadgeSection("Global badges - choose one for all chats", globalCatalog, false)
            addBadgeSection("This channel badges - choose one for this channel", channelCatalog, true)
            if (content.childCount == 0) {
                content.addView(TextView(requireContext()).apply {
                    text = "No badges are currently available to equip here, or Twitch did not return your badge collection."
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setPadding(0, (8 * resources.displayMetrics.density).roundToInt(), 0, (8 * resources.displayMetrics.density).roundToInt())
                })
            }
            content.addView(TextView(requireContext()).apply {
                text = "To change badges on your Twitch account, open this channel in Twitch, open Chat Identity beside the chat box, and choose your badges. Xtra will show the updated selection when Twitch reports it."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, (12 * resources.displayMetrics.density).roundToInt(), 0, 0)
            })
            val scrollView = android.widget.ScrollView(requireContext()).apply {
                addView(content)
            }
            val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
                ?: requireArguments().getString(KEY_CHANNEL_NAME)
            requireContext().getAlertDialogBuilder()
                .setTitle("Chat Identity")
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Open Twitch") { _, _ ->
                    val target = channelLogin?.takeIf { it.isNotBlank() } ?: "twitch"
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/$target")))
                    }.onFailure {
                        Toast.makeText(requireContext(), "Unable to open Twitch.", Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            }
        }
        binding.moderationLogsButton.setOnClickListener {
            val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                ?: requireArguments().getString(KEY_CHANNEL_LOGIN)
                ?: "channel"
            val logDialog = ModerationLogDialog.show(
                requireContext(),
                "Mod Logs · #$channelName",
                viewModel.moderationLogs.value,
                "No moderation events have been recorded for this channel yet."
            )
            val updateJob = viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.moderationLogs.collectLatest { entries -> logDialog.update(entries) }
                }
            }
            logDialog.dialog.setOnDismissListener { updateJob.cancel() }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.moderationLogs.collectLatest { entries ->
                    binding.moderationLogsButton.text = if (
                        binding.root.width > binding.root.height ||
                        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    ) {
                        if (entries.isEmpty()) "Logs" else "Logs (${entries.size})"
                    } else if (entries.isEmpty()) {
                        "Mod Logs"
                    } else {
                        "Mod Logs (${entries.size})"
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channelPointsBalance.collectLatest { balance ->
                    binding.channelPointsBar.isVisible = requireArguments().getBoolean(KEY_IS_LIVE) && !isMergedRendererOnly
                    binding.channelPointsBalance.text = balance?.let { NumberFormat.getIntegerInstance().format(it) } ?: "Loading…"
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.channelPointRewardsStatus.collectLatest { status ->
                    if (viewModel.channelPointsBalance.value == null) {
                        binding.channelPointsBalance.text =
                            if (status.startsWith("Loading")) "Loading…" else "Unavailable"
                    }
                }
            }
        }
        val showChannelPointsRewards = {
            val rewards = viewModel.channelPointRewards.value
            val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                ?: requireArguments().getString(KEY_CHANNEL_LOGIN) ?: "channel"
            val channelId = requireArguments().getString(KEY_CHANNEL_ID)
            val balance = viewModel.channelPointsBalance.value
            val balanceText = balance?.let { NumberFormat.getIntegerInstance().format(it) } ?: "—"
            val lines = rewards.map { reward ->
                val cost = reward.cost?.let { NumberFormat.getIntegerInstance().format(it) } ?: "?"
                val status = when {
                    reward.paused -> " · Paused"
                    !reward.enabled -> " · Unavailable"
                    !reward.inStock -> " · Out of stock"
                    balance != null && reward.cost != null && balance < reward.cost -> " · Not enough points"
                    else -> ""
                }
                "${reward.title}  ·  ◆ $cost$status"
            }
            val builder = requireContext().getAlertDialogBuilder()
                .setTitle("Channel Points · #$channelName")
                .setPositiveButton(android.R.string.ok, null)
            if (rewards.isEmpty()) {
                builder.setMessage(
                    "Your balance: $balanceText Channel Points\n\nNo custom rewards were loaded.\n\nDiagnostic: ${viewModel.channelPointRewardsStatus.value}"
                )
            } else {
                // AlertDialog cannot display a message and a selectable item list together.
                // Put the balance in the title so the reward rows remain visible and tappable.
                builder.setTitle("Channel Points · #$channelName\nYour balance: $balanceText points")
                builder.setItems(lines.toTypedArray()) { _, which ->
                    val reward = rewards[which]
                    val unavailableReason = when {
                        reward.paused -> "This reward is paused by the channel."
                        !reward.enabled -> "This reward is currently unavailable."
                        !reward.inStock -> "This reward is out of stock."
                        reward.cost == null -> "Twitch did not provide this reward's cost."
                        balance != null && balance < reward.cost -> "You do not have enough Channel Points."
                        channelId.isNullOrBlank() -> "The channel ID is missing."
                        else -> null
                    }
                    if (unavailableReason != null) {
                        Toast.makeText(requireContext(), unavailableReason, Toast.LENGTH_LONG).show()
                    } else {
                        val input = if (reward.userInputRequired) EditText(requireContext()).apply {
                            hint = reward.prompt?.takeIf { it.isNotBlank() } ?: "Enter the required text"
                            maxLines = 4
                        } else null
                        val confirmBuilder = requireContext().getAlertDialogBuilder()
                            .setTitle(reward.title ?: "Redeem reward")
                            .setMessage(buildString {
                                append("Redeem this reward for ")
                                append(NumberFormat.getIntegerInstance().format(reward.cost))
                                append(" Channel Points?")
                                reward.prompt?.takeIf { it.isNotBlank() && !reward.userInputRequired }?.let {
                                    append("\n\n$it")
                                }
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("Redeem", null)
                        if (input != null) confirmBuilder.setView(input)
                        val confirmDialog = confirmBuilder.create()
                        confirmDialog.setOnShowListener {
                            confirmDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val textInput = input?.text?.toString()?.trim().orEmpty()
                                if (reward.userInputRequired && textInput.isBlank()) {
                                    input?.error = "Please enter the required text"
                                    return@setOnClickListener
                                }
                                confirmDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                                viewModel.redeemChannelPointReward(
                                    channelId = channelId!!,
                                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                                    reward = reward,
                                    textInput = textInput,
                                ) { success, error ->
                                    if (!isAdded) return@redeemChannelPointReward
                                    Toast.makeText(
                                        requireContext(),
                                        if (success) "Reward redeemed" else "Redemption failed: ${error ?: "Unknown error"}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    if (success) confirmDialog.dismiss()
                                    else confirmDialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                }
                            }
                        }
                        confirmDialog.show()
                    }
                }
            }
            builder.show()
        }
        // Attach the action directly to each visible part instead of forwarding child taps.
        binding.channelPointsBar.setOnClickListener { showChannelPointsRewards() }
        binding.channelPointsIcon.setOnClickListener { showChannelPointsRewards() }
        binding.channelPointsBalance.setOnClickListener { showChannelPointsRewards() }
        binding.channelPointsLabel.setOnClickListener { showChannelPointsRewards() }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.pinnedChatMessage.collectLatest { pinned ->
                    binding.pinnedChatBar.isVisible = pinned != null && !isMergedRendererOnly
                    binding.pinnedChatText.text = pinned?.message?.text.orEmpty()
                    binding.pinnedChatBy.text = pinned?.let {
                        val pinnedBy = it.pinnedByName ?: it.pinnedByLogin ?: "moderator"
                        val sender = it.senderName ?: it.senderLogin ?: "viewer"
                        "Pinned by $pinnedBy · Sent by $sender"
                    }.orEmpty()
                    binding.pinnedChatExpiry.text = pinned?.expiresAt?.let { expiry ->
                        runCatching {
                            val time = java.time.Instant.parse(expiry).toEpochMilli()
                            "Expires " + DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        }.getOrNull()
                    } ?: if (pinned != null) "Until stream ends" else ""
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.watchStreak.collectLatest { streak ->
                    binding.watchStreakIndicator.isVisible = streak != null
                    binding.watchStreakIndicator.text = streak?.let { "🔥 ${it.count}" }.orEmpty()
                    binding.channelPointsBar.isVisible = requireArguments().getBoolean(KEY_IS_LIVE) && !isMergedRendererOnly
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.watchStreakStatus.collectLatest { status ->
                    if (viewModel.watchStreak.value == null) {
                        val loading = status.startsWith("Loading")
                        val unavailable = status != "Not loaded" && status != "No active watch streak returned by Twitch"
                        binding.watchStreakIndicator.isVisible = loading || unavailable
                        binding.watchStreakIndicator.text = when {
                            loading -> "🔥 Loading"
                            unavailable -> "🔥 Status"
                            else -> ""
                        }
                    }
                }
            }
        }
        binding.watchStreakIndicator.setOnClickListener {
            val streak = viewModel.watchStreak.value
            if (streak == null) {
                val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                    ?: requireArguments().getString(KEY_CHANNEL_LOGIN) ?: "channel"
                requireContext().getAlertDialogBuilder()
                    .setTitle("Watch Streak · #$channelName")
                    .setMessage(viewModel.watchStreakStatus.value)
                    .setPositiveButton("Retry") { _, _ ->
                        viewModel.refreshWatchStreakForUi(requireArguments().getString(KEY_CHANNEL_ID))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@setOnClickListener
            }
            val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                ?: requireArguments().getString(KEY_CHANNEL_LOGIN) ?: "channel"
            val expiry = streak.expiresAt?.let { value ->
                runCatching {
                    val time = java.time.Instant.parse(value).toEpochMilli()
                    "Recovery window ends " + DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                }.getOrNull()
            }
            val dialog = requireContext().getAlertDialogBuilder()
                .setTitle("Watch Streak · #$channelName")
                .setMessage(buildString {
                    append("🔥  ")
                    append(streak.count)
                    append(if (streak.count == 1) " consecutive stream" else " consecutive streams")
                    if (streak.threshold > 0) append("\nNext milestone: ${streak.threshold}")
                    if (streak.bonusPoints > 0) append("\nMilestone bonus: +${NumberFormat.getIntegerInstance().format(streak.bonusPoints)} Channel Points")
                    expiry?.let { append("\n$it") }
                })
            if (streak.shareStatus == "CAN_SHARE" && !streak.milestoneId.isNullOrBlank()) {
                dialog.setPositiveButton("Share Watch Streak") { _, _ ->
                    viewModel.shareWatchStreak(requireArguments().getString(KEY_CHANNEL_ID)) { success, error ->
                        Toast.makeText(
                            requireContext(),
                            if (success) "Watch Streak shared in chat" else "Watch Streak wasn't shared: ${error ?: "Unknown error"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                dialog.setNegativeButton(android.R.string.cancel, null)
            } else {
                dialog.setPositiveButton(android.R.string.ok, null)
            }
            dialog.show()
        }
        binding.pinnedChatBar.setOnClickListener {
            val pinned = viewModel.pinnedChatMessage.value ?: return@setOnClickListener
            val channelName = requireArguments().getString(KEY_CHANNEL_NAME)
                ?: requireArguments().getString(KEY_CHANNEL_LOGIN) ?: "channel"
            val messageText = pinned.message?.text.orEmpty()
            val pinnedBy = pinned.pinnedByName ?: pinned.pinnedByLogin ?: "moderator"
            val sender = pinned.senderName ?: pinned.senderLogin ?: "viewer"
            val pinnedAt = pinned.pinnedAt?.let { value ->
                runCatching {
                    val time = java.time.Instant.parse(value).toEpochMilli()
                    "Pinned " + DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                }.getOrNull()
            }
            val expiry = pinned.expiresAt?.let { value ->
                runCatching {
                    val time = java.time.Instant.parse(value).toEpochMilli()
                    "Expires " + DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                }.getOrNull()
            } ?: "Pinned until the stream ends"
            requireContext().getAlertDialogBuilder()
                .setTitle("Pinned message · #$channelName")
                .setMessage(buildString {
                    append(messageText.ifBlank { "Message content is unavailable." })
                    append("\\n\\nSent by ").append(sender)
                    append("\\nPinned by ").append(pinnedBy)
                    pinnedAt?.let { append("\\n").append(it) }
                    append("\\n").append(expiry)
                })
                .setPositiveButton("Copy message") { _, _ ->
                    if (messageText.isNotBlank()) {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Pinned chat message", messageText))
                        Toast.makeText(requireContext(), "Pinned message copied", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        binding.pinnedChatBar.setOnLongClickListener {
            val message = viewModel.pinnedChatMessage.value?.message?.text.orEmpty()
            if (message.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Pinned chat message", message))
                Toast.makeText(requireContext(), "Pinned message copied", Toast.LENGTH_SHORT).show()
                true
            } else false
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.integrity.collect {
                    (requireActivity() as? MainActivity)?.getNewIntegrityToken(it, childFragmentManager)
                }
            }
        }
        with(binding) {
            if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
                val args = requireArguments()
                val channelId = args.getString(KEY_CHANNEL_ID)
                val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
                val isLive = args.getBoolean(KEY_IS_LIVE)
                val accountLogin = requireContext().tokenPrefs().getString(C.USERNAME, null)
                val isLoggedIn = !accountLogin.isNullOrBlank() &&
                        (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                val chatUrl = args.getString(KEY_CHAT_URL)
                if (isLive || (args.getString(KEY_VIDEO_ID) != null && args.getInt(KEY_START_TIME) != -1) || chatUrl != null) {
                    val enableMessaging = isLive && isLoggedIn && !isMergedRendererOnly
                    val sizeModifier = (requireContext().prefs().getInt(C.CHAT_SIZE_MODIFIER, 100).toFloat() / 100f)
                    adapter = ChatAdapter(
                        messages = viewModel.chatMessages,
                        localTwitchEmotes = viewModel.localTwitchEmotes,
                        thirdPartyEmotes = viewModel.thirdPartyEmotes,
                        globalBadges = viewModel.globalBadges,
                        channelBadges = viewModel.channelBadges,
                        sourceChannelBadges = viewModel.sourceChannelBadges,
                        cheerEmotes = viewModel.cheerEmotes,
                        namePaints = viewModel.namePaints,
                        stvBadges = viewModel.stvBadges,
                        personalEmoteSets = viewModel.personalEmoteSets,
                        stvUsers = viewModel.stvUsers,
                        enableTimestamps = requireContext().prefs().getBoolean(C.CHAT_TIMESTAMPS, false),
                        timestampFormat = requireContext().prefs().getString(C.CHAT_TIMESTAMP_FORMAT, "0"),
                        firstMsgVisibility = requireContext().prefs().getString(C.CHAT_FIRST_MSG_VISIBILITY, "0")?.toIntOrNull() ?: 0,
                        firstChatMsg = getString(R.string.chat_first),
                        redeemedChatMsg = getString(R.string.redeemed),
                        redeemedNoMsg = getString(R.string.user_redeemed),
                        rewardChatMsg = getString(R.string.chat_reward),
                        replyMessage = getString(R.string.replying_to_message),
                        useRandomColors = requireContext().prefs().getBoolean(C.CHAT_RANDOM_COLOR, true),
                        useReadableColors = requireContext().prefs().getBoolean(C.CHAT_THEME_ADAPTED_USERNAME_COLOR, true),
                        isLightTheme = requireContext().obtainStyledAttributes(intArrayOf(androidx.appcompat.R.attr.isLightTheme)).use {
                            it.getBoolean(0, false)
                        },
                        nameDisplay = requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0"),
                        useBoldNames = requireContext().prefs().getBoolean(C.CHAT_BOLD_NAMES, false),
                        showNamePaints = requireContext().prefs().getBoolean(C.CHAT_SHOW_PAINTS, true),
                        showSTVBadges = requireContext().prefs().getBoolean(C.CHAT_SHOW_STV_BADGES, true),
                        showPersonalEmotes = requireContext().prefs().getBoolean(C.CHAT_SHOW_PERSONAL_EMOTES, true),
                        showSystemMessageEmotes = requireContext().prefs().getBoolean(C.CHAT_SYSTEM_MESSAGE_EMOTES, true),
                        chatUrl = chatUrl,
                        getEmoteBytes = viewModel::getEmoteBytes,
                        fragment = this@ChatFragment,
                        backgroundColor = MaterialColors.getColor(requireView(), com.google.android.material.R.attr.colorSurface),
                        dialogBackgroundColor = MaterialColors.getColor(
                            requireView(),
                            if (requireContext().prefs().getBoolean(C.UI_THEME_MATERIAL3, true)) {
                                com.google.android.material.R.attr.colorSurfaceContainerLow
                            } else {
                                com.google.android.material.R.attr.colorSurface
                            }
                        ),
                        imageLibrary = requireContext().prefs().getString(C.CHAT_IMAGE_LIBRARY, "0"),
                        messageTextSize = (requireContext().prefs().getString(C.CHAT_TEXT_SIZE, "14")?.toFloatOrNull() ?: 14f) * sizeModifier,
                        emoteSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (requireContext().prefs().getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f) * sizeModifier, resources.displayMetrics).toInt(),
                        badgeSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (requireContext().prefs().getString(C.CHAT_BADGE_SIZE, "18.5")?.toFloatOrNull() ?: 18.5f) * sizeModifier, resources.displayMetrics).toInt(),
                        gifSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (requireContext().prefs().getString(C.CHAT_GIF_SIZE, "79.5")?.toFloatOrNull() ?: 79.5f) * sizeModifier, resources.displayMetrics).toInt(),
                        showGifMessages = requireContext().prefs().getBoolean(C.CHAT_SHOW_GIF_MESSAGES, true),
                        emoteQuality = requireContext().prefs().getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4",
                        animateGifs = requireContext().prefs().getBoolean(C.ANIMATED_EMOTES, true),
                        enableOverlayEmotes = requireContext().prefs().getBoolean(C.CHAT_ZERO_WIDTH, true),
                        translateMessage = this@ChatFragment::onTranslateMessageClicked,
                        showLanguageDownloadDialog = this@ChatFragment::showLanguageDownloadDialog,
                        channelId = channelId,
                        loggedInUser = if (enableMessaging) accountLogin else null,
                        messageClickListener = { channelId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            MessageClickedDialog.newInstance(enableMessaging, channelId).show(this@ChatFragment.childFragmentManager, "messageDialog")
                        },
                        replyClickListener = {
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ReplyClickedDialog.newInstance(enableMessaging).show(this@ChatFragment.childFragmentManager, "replyDialog")
                        },
                        imageClickListener = { url, name, format, isAnimated, source, thirdParty, emoteId ->
                            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
                            editText.clearFocus()
                            ImageClickedDialog.newInstance(url, name, format, isAnimated, source, thirdParty, emoteId).show(this@ChatFragment.childFragmentManager, "imageDialog")
                        },
                    )
                    recyclerView.let {
                        it.adapter = adapter
                        it.itemAnimator = null
                        it.layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        it.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                super.onScrolled(recyclerView, dx, dy)
                                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                                val lastVisible = layoutManager?.findLastVisibleItemPosition() ?: -1
                                val itemCount = recyclerView.adapter?.itemCount ?: 0
                                val atBottom = itemCount == 0 || lastVisible >= itemCount - 1
                                // Upward movement always means the reader is browsing history.
                                // Only an actual downward scroll reaching the bottom resumes follow;
                                // dy == 0 can also happen during adapter/layout updates and must not
                                // pull the reader back to the newest message.
                                if (dy < 0) {
                                    isFollowingChat = false
                                    adapter?.pauseDisplay()
                                } else if (dy > 0 && atBottom) {
                                    isFollowingChat = true
                                    adapter?.resumeDisplay()
                                }
                                btnDown.isVisible = !isFollowingChat
                            }

                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                super.onScrollStateChanged(recyclerView, newState)
                                if (showChatStatus && chatStatus.isGone && newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                    chatStatus.visibility = View.VISIBLE
                                    chatStatus.postDelayed({ chatStatus.visibility = View.GONE }, 5000)
                                }
                            }
                        })
                    }
                    btnDown.setOnClickListener {
                        isFollowingChat = true
                        adapter?.resumeDisplay()
                        btnDown.text = getString(R.string.scroll_down)
                        view.post {
                            val lastIndex = synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.lastIndex
                            }
                            recyclerView.scrollToPosition(lastIndex)
                            btnDown.isGone = true
                        }
                    }
                    if (enableMessaging) {
                        viewModel.loadRecentEmotes()
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.hasRecentEmotes.collectLatest {
                                    if (it) {
                                        hasRecentEmotes = true
                                    }
                                }
                            }
                        }
                        autoCompleteAdapter = AutoCompleteAdapter(
                            requireContext(),
                            R.layout.auto_complete_emotes_list_item,
                            R.id.name,
                            viewModel.autoCompleteList,
                        ).apply {
                            setNotifyOnChange(false)
                            editText.setAdapter(this)

                            var previousSize = 0
                            editText.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus && count != previousSize) {
                                    previousSize = count
                                    notifyDataSetChanged()
                                }
                                setNotifyOnChange(hasFocus)
                            }
                        }
                        editText.addTextChangedListener(onTextChanged = { text, _, _, _ ->
                            if (text?.isNotBlank() == true) {
                                send.visibility = View.VISIBLE
                                clear.visibility = View.VISIBLE
                            } else {
                                send.visibility = View.GONE
                                clear.visibility = View.GONE
                            }
                        })
                        editText.setTokenizer(SpaceTokenizer())
                        editText.setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                                sendMessage()
                            } else {
                                false
                            }
                        }
                        clear.setOnClickListener {
                            val text = editText.text.toString().trimEnd()
                            editText.setText(text.substring(0, max(text.lastIndexOf(' '), 0)))
                            editText.setSelection(editText.length())
                        }
                        clear.setOnLongClickListener {
                            editText.text.clear()
                            true
                        }
                        replyView.visibility = View.GONE
                        send.setOnClickListener { sendMessage() }
                        if ((view.parent?.parent?.parent?.parent as? View)?.id == R.id.slidingLayout && !requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                            messageView.visibility = View.GONE
                        } else {
                            messageView.visibility = View.VISIBLE
                        }
                        viewPager.adapter = object : FragmentStateAdapter(this@ChatFragment) {
                            override fun getItemCount(): Int = 3

                            override fun createFragment(position: Int): Fragment {
                                return EmotesFragment.newInstance(position)
                            }
                        }
                        viewPager.offscreenPageLimit = 2
                        viewPager.reduceDragSensitivity()
                        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                            tab.text = when (position) {
                                0 -> getString(R.string.recent_emotes)
                                1 -> "Twitch"
                                else -> "7TV/BTTV/FFZ"
                            }
                        }.attach()
                        emotes.setOnClickListener {
                            //TODO add animation
                            if (emoteMenu.isGone) {
                                if (!hasRecentEmotes && viewPager.currentItem == 0) {
                                    viewPager.setCurrentItem(1, false)
                                }
                                toggleEmoteMenu(true)
                            } else {
                                toggleEmoteMenu(false)
                            }
                        }
                        messagingEnabled = true
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.roomState.collectLatest { roomState ->
                                if (roomState != null) {
                                    when (roomState.emote) {
                                        "0" -> textEmote.visibility = View.GONE
                                        "1" -> textEmote.visibility = View.VISIBLE
                                    }
                                    if (roomState.followers != null) {
                                        when (roomState.followers) {
                                            "-1" -> textFollowers.visibility = View.GONE
                                            "0" -> {
                                                textFollowers.text = getString(R.string.room_followers)
                                                textFollowers.visibility = View.VISIBLE
                                            }
                                            else -> {
                                                textFollowers.text = getString(
                                                    R.string.room_followers_min,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), (roomState.followers.toInt() * 60).toString())
                                                )
                                                textFollowers.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                    when (roomState.unique) {
                                        "0" -> textUnique.visibility = View.GONE
                                        "1" -> textUnique.visibility = View.VISIBLE
                                    }
                                    if (roomState.slow != null) {
                                        when (roomState.slow) {
                                            "0" -> textSlow.visibility = View.GONE
                                            else -> {
                                                textSlow.text = getString(
                                                    R.string.room_slow,
                                                    TwitchApiHelper.getDurationFromSeconds(requireContext(), roomState.slow)
                                                )
                                                textSlow.visibility = View.VISIBLE
                                            }
                                        }
                                    }
                                    when (roomState.subs) {
                                        "0" -> textSubs.visibility = View.GONE
                                        "1" -> textSubs.visibility = View.VISIBLE
                                    }
                                    if (textEmote.isGone && textFollowers.isGone && textUnique.isGone && textSlow.isGone && textSubs.isGone) {
                                        showChatStatus = false
                                        chatStatus.visibility = View.GONE
                                    } else {
                                        showChatStatus = true
                                        chatStatus.visibility = View.VISIBLE
                                        chatStatus.postDelayed({ chatStatus.visibility = View.GONE }, 5000)
                                    }
                                    viewModel.roomState.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.reloadMessages.collectLatest { reloadVersion ->
                                if (reloadVersion > 0) {
                                    // Emote and badge resources affect visible rows; rebinding the
                                    // entire history repeatedly restarts image requests and can make
                                    // already-loaded emotes blink while provider results arrive.
                                    adapter?.let { currentAdapter ->
                                        val layoutManager = binding.recyclerView.layoutManager as? LinearLayoutManager
                                        val first = layoutManager?.findFirstVisibleItemPosition() ?: RecyclerView.NO_POSITION
                                        val last = layoutManager?.findLastVisibleItemPosition() ?: RecyclerView.NO_POSITION
                                        if (first != RecyclerView.NO_POSITION && last >= first) {
                                            currentAdapter.notifyItemRangeChanged(first, last - first + 1)
                                        }
                                        (parentFragment as? PlayerFragment)?.refreshMergedChatSource(currentAdapter)
                                    }
                                    messageDialog?.adapter?.let { currentAdapter ->
                                        val size = synchronized(currentAdapter.messages) { currentAdapter.messages.size }
                                        if (size > 0) currentAdapter.notifyItemRangeChanged(0, size)
                                    }
                                    replyDialog?.adapter?.let { currentAdapter ->
                                        val size = synchronized(currentAdapter.messages) { currentAdapter.messages.size }
                                        if (size > 0) currentAdapter.notifyItemRangeChanged(0, size)
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hideRaid.collectLatest {
                                if (it) {
                                    raidLayout.visibility = View.GONE
                                    viewModel.raidClosed = true
                                    viewModel.hideRaid.value = false
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raid.collectLatest { raid ->
                                if (raid != null) {
                                    if (!viewModel.raidClosed) {
                                        if (raid.openStream) {
                                            if (requireContext().prefs().getBoolean(C.CHAT_RAIDS_AUTO_SWITCH, true) && parentFragment is PlayerFragment) {
                                                (requireActivity() as? MainActivity)?.startStream(
                                                    Stream(
                                                        channelId = raid.targetId,
                                                        channelLogin = raid.targetLogin,
                                                        channelName = raid.targetName,
                                                        channelImageURL = raid.targetImageURL,
                                                    )
                                                )
                                            }
                                            raidLayout.visibility = View.GONE
                                            viewModel.raidClosed = true
                                        } else {
                                            raidLayout.visibility = View.VISIBLE
                                            raidLayout.setOnClickListener { viewModel.raidClicked.value = raid }
                                            requireContext().imageLoader.enqueue(
                                                ImageRequest.Builder(requireContext()).apply {
                                                    data(raid.targetImage)
                                                    if (requireContext().prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) {
                                                        transformations(CircleCropTransformation())
                                                    }
                                                    crossfade(true)
                                                    target(raidImage)
                                                }.build()
                                            )
                                            raidClose.setOnClickListener {
                                                raidLayout.visibility = View.GONE
                                                viewModel.raidClosed = true
                                            }
                                            raidText.text = getString(
                                                R.string.raid_text,
                                                if (raid.targetLogin != null && !raid.targetLogin.equals(raid.targetName, true)) {
                                                    when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                                                        "0" -> "${raid.targetName}(${raid.targetLogin})"
                                                        "1" -> raid.targetName
                                                        else -> raid.targetLogin
                                                    }
                                                } else {
                                                    raid.targetName
                                                },
                                                raid.viewerCount
                                            )
                                        }
                                    }
                                    viewModel.raid.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.raidClicked.collectLatest {
                                if (it != null) {
                                    (requireActivity() as? MainActivity)?.startStream(
                                        Stream(
                                            channelId = it.targetId,
                                            channelLogin = it.targetLogin,
                                            channelName = it.targetName,
                                            channelImageURL = it.targetImageURL,
                                        )
                                    )
                                    viewModel.raidClicked.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hidePoll.collectLatest {
                                if (it) {
                                    pollLayout.visibility = View.GONE
                                    viewModel.pollSecondsLeft.value = null
                                    viewModel.pollTimer?.cancel()
                                    viewModel.pollClosed = true
                                    viewModel.hidePoll.value = false
                                }
                            }
                        }
                    }
                    pollClose.setOnClickListener {
                        pollLayout.visibility = View.GONE
                        viewModel.pollSecondsLeft.value = null
                        viewModel.pollTimer?.cancel()
                        viewModel.pollClosed = true
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.poll.collectLatest { poll ->
                                if (poll != null) {
                                    if (!viewModel.pollClosed) {
                                        when (poll.status) {
                                            "ACTIVE" -> {
                                                pollLayout.visibility = View.VISIBLE
                                                pollTitle.text = getString(R.string.poll_title, poll.title)
                                                pollChoices.text = poll.choices?.joinToString("\n") {
                                                    getString(
                                                        R.string.poll_choice,
                                                        (((it.totalVotes ?: 0).toLong() * 100.0) / max((poll.totalVotes ?: 0), 1)).roundToInt(),
                                                        it.totalVotes?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                pollStatus.visibility = View.VISIBLE
                                            }
                                            "COMPLETED", "TERMINATED" -> {
                                                pollLayout.visibility = View.VISIBLE
                                                pollTitle.text = getString(R.string.poll_title, poll.title)
                                                val winningTotal = poll.choices?.maxOfOrNull { it.totalVotes ?: 0 } ?: 0
                                                pollChoices.text = poll.choices?.joinToString("\n") {
                                                    getString(
                                                        if (winningTotal == it.totalVotes) {
                                                            R.string.poll_choice_winner
                                                        } else {
                                                            R.string.poll_choice
                                                        },
                                                        (((it.totalVotes ?: 0).toLong() * 100.0) / max((poll.totalVotes ?: 0), 1)).roundToInt(),
                                                        it.totalVotes?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                pollStatus.visibility = View.GONE
                                                viewModel.pollSecondsLeft.value = null
                                                viewModel.pollTimer?.cancel()
                                                viewModel.startPollTimeout { pollLayout.visibility = View.GONE }
                                            }
                                            else -> {
                                                pollLayout.visibility = View.GONE
                                                viewModel.pollSecondsLeft.value = null
                                                viewModel.pollTimer?.cancel()
                                                viewModel.pollClosed = true
                                            }
                                        }
                                    }
                                    viewModel.poll.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.pollSecondsLeft.collectLatest {
                                if (it != null) {
                                    pollStatus.text = getString(R.string.remaining_time, DateUtils.formatElapsedTime(it.toLong()))
                                    if (it <= 0) {
                                        viewModel.pollSecondsLeft.value = null
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.hidePrediction.collectLatest {
                                if (it) {
                                    predictionLayout.visibility = View.GONE
                                    viewModel.predictionSecondsLeft.value = null
                                    viewModel.predictionTimer?.cancel()
                                    viewModel.predictionClosed = true
                                    viewModel.hidePrediction.value = false
                                }
                            }
                        }
                    }
                    predictionClose.setOnClickListener {
                        predictionLayout.visibility = View.GONE
                        viewModel.predictionSecondsLeft.value = null
                        viewModel.predictionTimer?.cancel()
                        viewModel.predictionClosed = true
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.prediction.collectLatest { prediction ->
                                if (prediction != null) {
                                    if (!viewModel.predictionClosed) {
                                        when (prediction.status) {
                                            "ACTIVE" -> {
                                                predictionLayout.visibility = View.VISIBLE
                                                predictionTitle.text = getString(R.string.prediction_title, prediction.title)
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.joinToString("\n") {
                                                    getString(
                                                        R.string.prediction_outcome,
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints?.let { NumberFormat.getInstance().format(it) },
                                                        it.totalUsers?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                predictionStatus.visibility = View.VISIBLE
                                            }
                                            "LOCKED" -> {
                                                predictionLayout.visibility = View.VISIBLE
                                                predictionTitle.text = getString(R.string.prediction_title, prediction.title)
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.joinToString("\n") {
                                                    getString(
                                                        R.string.prediction_outcome,
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints?.let { NumberFormat.getInstance().format(it) },
                                                        it.totalUsers?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.startPredictionTimeout { predictionLayout.visibility = View.GONE }
                                                predictionStatus.visibility = View.VISIBLE
                                                predictionStatus.text = getString(R.string.prediction_locked)
                                            }
                                            "CANCELED", "CANCEL_PENDING", "RESOLVED", "RESOLVE_PENDING" -> {
                                                predictionLayout.visibility = View.VISIBLE
                                                predictionTitle.text = getString(R.string.prediction_title, prediction.title)
                                                val resolved = prediction.status == "RESOLVED" || prediction.status == "RESOLVE_PENDING"
                                                val totalPoints = prediction.outcomes?.sumOf { it.totalPoints?.toLong() ?: 0 } ?: 0
                                                predictionOutcomes.text = prediction.outcomes?.joinToString("\n") {
                                                    getString(
                                                        if (resolved && prediction.winningOutcomeId != null && prediction.winningOutcomeId == it.id) {
                                                            R.string.prediction_outcome_winner
                                                        } else {
                                                            R.string.prediction_outcome
                                                        },
                                                        (((it.totalPoints ?: 0).toLong() * 100.0) / max(totalPoints, 1)).roundToInt(),
                                                        it.totalPoints?.let { NumberFormat.getInstance().format(it) },
                                                        it.totalUsers?.let { NumberFormat.getInstance().format(it) },
                                                        it.title
                                                    )
                                                }
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.startPredictionTimeout { predictionLayout.visibility = View.GONE }
                                                if (resolved) {
                                                    predictionStatus.visibility = View.GONE
                                                } else {
                                                    predictionStatus.visibility = View.VISIBLE
                                                    predictionStatus.text = getString(R.string.prediction_refunded)
                                                }
                                            }
                                            else -> {
                                                predictionLayout.visibility = View.GONE
                                                viewModel.predictionSecondsLeft.value = null
                                                viewModel.predictionTimer?.cancel()
                                                viewModel.predictionClosed = true
                                            }
                                        }
                                    }
                                    viewModel.prediction.value = null
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.predictionSecondsLeft.collectLatest {
                                if (it != null) {
                                    predictionStatus.text = getString(R.string.remaining_time, DateUtils.formatElapsedTime(it.toLong()))
                                    if (it <= 0) {
                                        viewModel.predictionSecondsLeft.value = null
                                    }
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.playbackMessage.collectLatest {
                                if (it != null) {
                                    if (it.live != null) {
                                        (parentFragment as? PlayerFragment)?.updateLiveStatus(it.live, it.serverTime, channelLogin)
                                    }
                                    (parentFragment as? PlayerFragment)?.updateViewerCountFromChat(it.viewers, channelLogin)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.streamInfo.collectLatest {
                                if (it != null) {
                                    (parentFragment as? PlayerFragment)?.updateStreamInfo(it.title, it.gameId, null, it.gameName)
                                }
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            // Messages can continue arriving while this tab is stopped, when
                            // the SharedFlow has no collector. Re-sync the adapter on resume.
                            adapter?.notifyDataSetChanged()
                            viewModel.newMessage.collect { result ->
                                val message = result.first
                                val lastIndex = result.second
                                val removeCount = result.third
                                adapter?.let { adapter ->
                                    // Follow mode is controlled by the user's scroll position.
                                    // Do not re-check a transient "was at bottom" snapshot for every
                                    // replay message: RecyclerView can report a stale last-visible row
                                    // while it is laying out the previous insertion.
                                    val newLastIndex = lastIndex - 1
                                    if (adapter.isDisplayPaused()) {
                                        adapter.onIncomingMessageWhilePaused()
                                        binding.btnDown.text = "↓ " + adapter.pendingMessageCount
                                    } else if (removeCount > 0) {
                                        // The backing list has already been trimmed and appended as
                                        // one operation, so a full refresh avoids mismatched
                                        // intermediate RecyclerView item counts at the cap.
                                        adapter.notifyDataSetChanged()
                                    } else if (newLastIndex >= 0) {
                                        adapter.notifyItemInserted(newLastIndex)
                                    }
                                    if (!adapter.isDisplayPaused() && isFollowingChat && newLastIndex >= 0) {
                                        binding.recyclerView.scrollToPosition(newLastIndex)
                                        binding.btnDown.isGone = true
                                    }
                                }
                                messageDialog?.newMessage(message)
                                replyDialog?.newMessage(message)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.addMessages.collect { result ->
                                val messages = result.first
                                val lastIndex = result.second
                                adapter?.let { adapter ->
                                    if (!adapter.isDisplayPaused()) adapter.notifyItemRangeInserted(0, messages.size)
                                    if (!adapter.isDisplayPaused() && isFollowingChat && binding.btnDown.isGone) {
                                        binding.recyclerView.scrollToPosition(lastIndex)
                                    }
                                }
                                messageDialog?.addMessages(messages)
                                replyDialog?.addMessages(messages)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.removeMessages.collect { size ->
                                if (adapter?.isDisplayPaused() != true) adapter?.notifyItemRangeRemoved(0, size)
                            }
                        }
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repeatOnLifecycle(Lifecycle.State.STARTED) {
                            viewModel.updateUserMessages.collectLatest { userId ->
                                adapter?.let { adapter ->
                                    synchronized(viewModel.chatMessages) {
                                        viewModel.chatMessages.mapIndexedNotNull { index, message ->
                                            if (message.userId != null && message.userId == userId) {
                                                index
                                            } else null
                                        }
                                    }.forEach {
                                        adapter.notifyItemChanged(it)
                                    }
                                }
                                messageDialog?.updateUserMessages(userId)
                                replyDialog?.updateUserMessages(userId)
                            }
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && channelId != null && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                        viewLifecycleOwner.lifecycleScope.launch {
                            repeatOnLifecycle(Lifecycle.State.STARTED) {
                                viewModel.translateAllMessages.collectLatest {
                                    if (it != null) {
                                        adapter?.translateAllMessages = it
                                    }
                                }
                            }
                        }
                        viewModel.checkTranslateAllMessages(channelId)
                    }
                    if (chatUrl != null) {
                        viewModel.startReplay(
                            channelId = channelId,
                            channelLogin = channelLogin,
                            chatUrl = chatUrl,
                            createdAt = args.getString(KEY_CREATED_AT),
                            getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                            getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
                        )
                    }
                } else {
                    chatReplayUnavailable.visibility = View.VISIBLE
                }
            }
            if ((view.parent?.parent?.parent?.parent as? View)?.id != R.id.slidingLayout) {
                ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                    if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                        val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                            bottomMargin = insets.bottom
                        }
                    }
                    WindowInsetsCompat.CONSUMED
                }
            }
        }
    }

    override fun initialize() {
        if (!requireContext().prefs().getBoolean(C.CHAT_DISABLE, false)) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (isMergedRendererOnly) {
                viewModel.loadRendererResources(channelId, channelLogin)
            } else if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.startLive(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    requireContext().prefs().getString(C.CHAT_RECENT_MESSAGES_URL, "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel"),
                    channelId,
                    channelLogin,
                    args.getString(KEY_CHANNEL_NAME),
                    args.getString(KEY_STREAM_ID)
                )
            } else {
                val videoId = args.getString(KEY_VIDEO_ID)
                val startTime = args.getInt(KEY_START_TIME)
                if (videoId != null && startTime != -1) {
                    viewModel.startReplay(
                        channelId = channelId,
                        channelLogin = channelLogin,
                        videoId = videoId,
                        createdAt = args.getString(KEY_CREATED_AT),
                        startTime = startTime,
                        getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                        getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val args = requireArguments()
        val channelId = args.getString(KEY_CHANNEL_ID)
        val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
        if (isMergedRendererOnly) {
            return
        }
        if (args.getBoolean(KEY_IS_LIVE)) {
            viewModel.refreshPinnedChatMessage(channelId)
            viewModel.resumeLive(channelId, channelLogin)
        } else {
            viewModel.resumeReplay(
                channelId = channelId,
                channelLogin = channelLogin,
                chatUrl = args.getString(KEY_CHAT_URL),
                videoId = args.getString(KEY_VIDEO_ID),
                createdAt = args.getString(KEY_CREATED_AT),
                startTime = args.getInt(KEY_START_TIME),
                getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
            )
        }
    }

    private var pausedForPip = false

    fun setPipDisplayPaused(paused: Boolean) {
        if (paused) {
            if (!pausedForPip && adapter?.isDisplayPaused() != true) {
                adapter?.pauseDisplay()
                pausedForPip = true
            }
        } else if (pausedForPip) {
            adapter?.resumeDisplay()
            pausedForPip = false
        }
    }

    /** Rebind visible messages after PiP so failed third-party emote requests get another chance. */
    fun refreshImagesAfterPip() {
        if (isAdded && view != null && !isMergedRendererOnly && adapter?.isDisplayPaused() != true) {
            adapter?.notifyDataSetChanged()
        }
    }

    fun isActive(): Boolean? {
        return viewModel.isActive()
    }

    fun disconnect() {
        viewModel.disconnect()
    }

    fun reconnect() {
        val channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN)
        if (channelLogin != null) {
            viewModel.startLiveChat(requireArguments().getString(KEY_CHANNEL_ID), channelLogin)
            if (requireContext().prefs().getBoolean(C.CHAT_RECENT, true)) {
                viewModel.loadRecentMessages(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    requireContext().prefs().getString(C.CHAT_RECENT_MESSAGES_URL, "https://recent-messages.robotty.de/api/v2/recent-messages/\$channel"),
                    channelLogin,
                )
            }
        }
        viewModel.autoReconnect = true
    }

    fun reloadEmotes() {
        viewModel.reloadEmotes(
            requireArguments().getString(KEY_CHANNEL_ID),
            requireArguments().getString(KEY_CHANNEL_LOGIN)
        )
    }

    private fun resumeReplayFollowing() {
        isFollowingChat = true
        _binding?.let { current ->
            current.btnDown.isGone = true
            current.recyclerView.post {
                val lastIndex = synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.lastIndex
                }
                if (lastIndex >= 0) current.recyclerView.scrollToPosition(lastIndex)
            }
        }
        (parentFragment as? PlayerFragment)?.resumeMergedChatFollowing()
    }

    fun startReplayChatLoad() {
        resumeReplayFollowing()
        viewModel.startReplayChatLoad()
    }

    fun updatePosition(position: Long) {
        // A deliberate seek, like Twitch's replay panel, returns chat to follow mode.
        resumeReplayFollowing()
        viewModel.updatePosition(position)
    }

    fun updateSpeed(speed: Float) {
        viewModel.updateSpeed(speed)
    }

    fun updateStreamId(id: String?) {
        viewModel.streamId = id
    }

    fun getTranslateAllMessages(): Boolean {
        return viewModel.translateAllMessages.value == true
    }

    fun saveTranslatedChannel(channelId: String) {
        viewModel.translateAllMessages.value = true
        viewModel.saveTranslatedChannel(channelId)
    }

    fun deleteTranslatedChannel(channelId: String) {
        viewModel.translateAllMessages.value = false
        viewModel.deleteTranslatedChannel(channelId)
    }

    fun emoteMenuIsVisible() = binding.emoteMenu.isVisible

    fun toggleEmoteMenu(enable: Boolean) {
        if (enable) {
            binding.emoteMenu.visibility = View.VISIBLE
        } else {
            binding.emoteMenu.visibility = View.GONE
        }
        toggleBackPressedCallback(enable)
    }

    fun toggleBackPressedCallback(enable: Boolean) {
        if (enable) {
            requireActivity().onBackPressedDispatcher.addCallback(this, backPressedCallback)
        } else {
            backPressedCallback.remove()
        }
    }

    fun appendEmote(emote: Emote) {
        binding.editText.text.append(emote.name).append(' ')
    }

    private fun showDropsProgress() {
        if (!isAdded) return
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val channelId = arguments?.getString(KEY_CHANNEL_ID)
        val channelLogin = arguments?.getString(KEY_CHANNEL_LOGIN)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val status = TextView(context).apply {
            text = "Loading your Twitch Drops…"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextIsSelectable(true)
            setPadding(0, dp(4), 0, dp(10))
        }
        val refreshButton = android.widget.Button(context).apply {
            text = "Refresh progress"
            isAllCaps = false
        }
        val campaignsHeader = TextView(context).apply {
            text = "YOUR CAMPAIGNS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        }
        val campaignList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(status)
        content.addView(refreshButton)
        content.addView(campaignsHeader)
        content.addView(campaignList)
        val scroll = android.widget.ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        val dialog = context.getAlertDialogBuilder()
            .setTitle("Drops & Rewards")
            .setView(scroll)
            .setPositiveButton("Open Inventory") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/drops/inventory")))
                }.onFailure {
                    Toast.makeText(context, "No browser is available to open Twitch Drops.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.ok, null)
            .setNeutralButton("Copy status", null)
            .show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Twitch Drops status", status.text))
            Toast.makeText(context, "Drops status copied.", Toast.LENGTH_SHORT).show()
        }

        // Persist accepted claims across dashboard reopen/recreation while Twitch's
        // inventory query catches up. Claim IDs include the account ID, so entries are
        // naturally account-scoped; keep the set bounded.
        val acceptedClaimsPrefsKey = "drops_locally_accepted_claim_ids"
        val locallyAcceptedDropClaims = context.prefs()
            .getStringSet(acceptedClaimsPrefsKey, emptySet())
            ?.toMutableSet() ?: mutableSetOf()

        fun refreshDrops() {
            if (!isAdded || !dialog.isShowing) return
            refreshButton.isEnabled = false
            refreshButton.text = "Refreshing…"
            status.text = "Checking Twitch progress and campaign rewards…"
            campaignList.removeAllViews()
            viewLifecycleOwner.lifecycleScope.launch {
                val app = context.applicationContext as XtraApp
                val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                val headers = TwitchApiHelper.getGQLHeaders(context, true)
                val sessionResult = if (!channelId.isNullOrBlank() || !channelLogin.isNullOrBlank()) {
                    runCatching {
                        app.xtraModule.graphQLRepository.loadCurrentDropSession(
                            networkLibrary = networkLibrary,
                            headers = headers,
                            channelId = channelId,
                            channelLogin = channelLogin
                        )
                    }
                } else null
                val inventoryResult = runCatching {
                    app.xtraModule.graphQLRepository.loadDropsInventory(networkLibrary, headers)
                }
                val dashboardResult = runCatching {
                    app.xtraModule.graphQLRepository.loadDropsDashboard(networkLibrary, headers)
                }
                val availableDropsResult = if (!channelId.isNullOrBlank()) runCatching {
                    app.xtraModule.graphQLRepository.loadAvailableChannelDrops(networkLibrary, headers, channelId)
                } else null
                if (!isAdded || _binding == null || !dialog.isShowing) return@launch
                refreshButton.isEnabled = true
                refreshButton.text = "Refresh progress"

                val session = sessionResult?.getOrNull()?.let { response ->
                    runCatching {
                        JSONObject(response).optJSONObject("data")
                            ?.optJSONObject("currentUser")?.optJSONObject("dropCurrentSession")
                    }.getOrNull()
                }
                val minutes = session?.optInt("currentMinutesWatched", -1) ?: -1
                val dropId = session?.optString("dropID")?.takeIf { it.isNotBlank() && it != "null" }
                val trackerStatus = context.prefs().getString(
                    "drops_watch_diagnostic",
                    "No tracker activity recorded yet."
                )
                status.text = buildString {
                    if (minutes >= 0 && dropId != null) {
                        append("LIVE SESSION  ·  ")
                        append(minutes)
                        append(" minutes watched")
                    } else {
                        append("No active Drops watch session reported for this channel.")
                    }
                    append("\nProgress is confirmed by Twitch, not estimated by Xtra.")
                    append("\n\nXTRA TRACKER\n")
                    append(trackerStatus)
                }

                val inventoryText = inventoryResult.getOrNull()
                val inventory = inventoryText?.let { response ->
                    runCatching {
                        JSONObject(response).optJSONObject("data")
                            ?.optJSONObject("currentUser")?.optJSONObject("inventory")
                    }.getOrNull()
                }
                // Inventory contains campaigns already in progress. The dashboard is a
                // separate Twitch source and can list the active campaign before it appears
                // in inventory, so resolve active campaign details as a fallback.
                val campaigns = org.json.JSONArray()
                val campaignIds = mutableSetOf<String>()
                fun addCampaign(campaign: JSONObject?) {
                    campaign ?: return
                    val id = campaign.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: return
                    if (campaignIds.add(id)) campaigns.put(campaign)
                }
                val inventoryCampaigns = inventory?.optJSONArray("dropCampaignsInProgress")
                if (inventoryCampaigns != null) {
                    for (index in 0 until inventoryCampaigns.length()) {
                        addCampaign(inventoryCampaigns.optJSONObject(index))
                    }
                }
                val availableDropsText = availableDropsResult?.getOrNull()
                val channelCampaigns = availableDropsText?.let { response ->
                    runCatching {
                        val root = JSONObject(response).optJSONObject("data")
                        root?.optJSONObject("channel")?.optJSONArray("viewerDropCampaigns")
                    }.getOrNull()
                }
                if (channelCampaigns != null) {
                    for (index in 0 until channelCampaigns.length()) {
                        addCampaign(channelCampaigns.optJSONObject(index))
                    }
                }
                val dashboardText = dashboardResult.getOrNull()
                val dashboardCampaigns = dashboardText?.let { response ->
                    runCatching {
                        JSONObject(response).optJSONObject("data")
                            ?.optJSONObject("currentUser")?.optJSONArray("dropCampaigns")
                    }.getOrNull()
                }
                val accountLogin = context.tokenPrefs().getString(C.USERNAME, null)
                if (dashboardCampaigns != null) {
                    var resolved = 0
                    for (index in 0 until dashboardCampaigns.length()) {
                        val summary = dashboardCampaigns.optJSONObject(index) ?: continue
                        if (!summary.optString("status").equals("ACTIVE", true)) continue
                        val campaignId = summary.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
                        if (campaignIds.contains(campaignId) || resolved >= 12) continue
                        val details = runCatching {
                            app.xtraModule.graphQLRepository.loadDropCampaignDetails(
                                networkLibrary, headers, campaignId, accountLogin
                            )
                        }.getOrNull() ?: continue
                        val detailCampaign = runCatching {
                            JSONObject(details).optJSONObject("data")
                                ?.optJSONObject("user")?.optJSONObject("dropCampaign")
                        }.getOrNull()
                        if (detailCampaign != null) {
                            addCampaign(detailCampaign)
                            resolved++
                        }
                    }
                }
                if (campaigns.length() == 0 && inventoryResult.isFailure && dashboardResult.isFailure) {
                    val message = if (inventoryText?.contains("PersistedQueryNotFound", true) == true ||
                        dashboardText?.contains("PersistedQueryNotFound", true) == true) {
                        "Twitch did not accept the Drops campaign queries. Open Inventory on Twitch to view your campaigns."
                    } else {
                        "Couldn't load campaign details right now. Your watch tracking is still running."
                    }
                    campaignList.addView(TextView(context).apply {
                        text = message
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(4), 0, dp(12))
                    })
                    return@launch
                }

                if (campaigns.length() == 0) {
                    campaignList.addView(TextView(context).apply {
                        text = "Twitch did not return any active watchtime campaigns for this account. Check All Campaigns in your Drops Inventory for campaigns you can join."
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setPadding(0, dp(4), 0, dp(12))
                    })
                    return@launch
                }

                for (campaignIndex in 0 until campaigns.length()) {
                    val campaign = campaigns.optJSONObject(campaignIndex) ?: continue
                    val game = campaign.optJSONObject("game")
                    val gameName = game?.optString("displayName")?.takeIf { it.isNotBlank() }
                        ?: game?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: campaign.optString("name", "Drops campaign")
                    val campaignName = campaign.optString("name").takeIf { it.isNotBlank() }
                        ?: gameName
                    val isGtaviCelebration = gameName.contains("Grand Theft Auto VI", true) ||
                        campaignName.contains("GTAVI Launch Celebration", true)
                    val card = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(com.google.android.material.color.MaterialColors.getColor(
                                context, com.google.android.material.R.attr.colorSurface, 0xFF252525.toInt()
                            ))
                            cornerRadius = dp(12).toFloat()
                            setStroke(dp(1), 0x557F7F7F)
                        }
                    }
                    val heading = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val artUrl = (campaign.optString("imageURL").takeIf { it.startsWith("https://", true) }
                        ?: game?.optString("boxArtURL")?.takeIf { it.startsWith("https://", true) }
                        ?: campaign.optString("gameBoxArtURL").takeIf { it.startsWith("https://", true) })
                    if (!artUrl.isNullOrBlank()) {
                        val art = android.widget.ImageView(context).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(48), dp(64))
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            contentDescription = "$gameName artwork"
                        }
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context).apply {
                                data(artUrl)
                                crossfade(true)
                                target(art)
                            }.build()
                        )
                        heading.addView(art)
                    }
                    val titleColumn = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(if (artUrl.isNullOrBlank()) 0 else dp(10), 0, 0, 0)
                    }
                    titleColumn.addView(TextView(context).apply {
                        text = gameName
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    })
                    titleColumn.addView(TextView(context).apply {
                        text = campaignName
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    })
                    heading.addView(titleColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    card.addView(heading)

                    val self = campaign.optJSONObject("self")
                    val linked = self?.optBoolean("isAccountConnected", false) == true
                    val drops = campaign.optJSONArray("timeBasedDrops")
                    if (drops == null || drops.length() == 0) {
                        card.addView(TextView(context).apply {
                            text = "No watchtime rewards listed in the active inventory."
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, dp(8), 0, 0)
                        })
                    } else {
                        for (dropIndex in 0 until drops.length()) {
                            val drop = drops.optJSONObject(dropIndex) ?: continue
                            val dropSelf = drop.optJSONObject("self")
                            val required = drop.optInt("requiredMinutesWatched", 0).coerceAtLeast(0)
                            val requiredSubs = drop.optInt("requiredSubs", 0).coerceAtLeast(0)
                            val inventoryWatched = dropSelf?.optInt("currentMinutesWatched", 0)?.coerceAtLeast(0) ?: 0
                            val watched = if (drop.optString("id") == dropId && minutes >= 0) {
                                maxOf(inventoryWatched, minutes)
                            } else inventoryWatched
                            val serverClaimed = dropSelf?.optBoolean("isClaimed", false) == true
                            // Prefer Twitch's real instance ID. The GTA VI DVD Chest is a
                            // standard multi-day Drops reward; Twitch Drops clients also support
                            // its deterministic userID#campaignID#dropID claim ID fallback.
                            val twitchClaimId = dropSelf?.optString("dropInstanceID")
                                ?.takeIf { !it.isNullOrBlank() && it != "null" }
                            // Twitch returns benefitEdges as either a direct array or a
                            // connection object containing edges/nodes, depending on the query.
                            val benefitEdgesValue = drop.opt("benefitEdges")
                            val benefits = when (benefitEdgesValue) {
                                is org.json.JSONArray -> benefitEdgesValue
                                is JSONObject -> benefitEdgesValue.optJSONArray("edges")
                                    ?: benefitEdgesValue.optJSONArray("nodes")
                                else -> null
                            }
                            fun benefitAt(index: Int): JSONObject? {
                                val edge = benefits?.optJSONObject(index) ?: return null
                                return edge.optJSONObject("benefit") ?: edge
                            }
                            val rewardNames = mutableListOf<String>()
                            var hasBadgeBenefit = false
                            if (benefits != null) {
                                for (benefitIndex in 0 until benefits.length()) {
                                    val benefit = benefitAt(benefitIndex) ?: continue
                                    if (benefit.optString("distributionType").equals("BADGE", true)) {
                                        hasBadgeBenefit = true
                                    }
                                    benefit.optString("name")
                                        .takeIf { it.isNotBlank() && it != "null" }
                                        ?.let(rewardNames::add)
                                }
                            }
                            val timedDropName = drop.optString("name")
                                .takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                            val rewardName = rewardNames.distinct().joinToString(" · ")
                                .ifBlank { timedDropName.ifBlank { "Drop reward" } }
                            // Twitch may put the actual badge name in benefitEdges while
                            // keeping "GTA DVD Chest" only on the timed-drop object.
                            val isGtaviDvdChest = isGtaviCelebration &&
                                (timedDropName.contains("GTA DVD Chest", true) ||
                                    rewardName.contains("GTA DVD Chest", true))
                            val displayRewardName = if (isGtaviDvdChest && rewardNames.isEmpty()) {
                                "Daily chain badge"
                            } else rewardName
                            val accountUserId = context.tokenPrefs().getString(C.USER_ID, null)
                                ?.takeIf { it.matches(Regex("\\d+")) }
                            val campaignId = campaign.optString("id")
                                .takeIf { it.isNotBlank() && it != "null" }
                            val timedDropId = drop.optString("id")
                                .takeIf { it.isNotBlank() && it != "null" }
                            val claimId = twitchClaimId ?: if (isGtaviDvdChest &&
                                !accountUserId.isNullOrBlank() && !campaignId.isNullOrBlank() &&
                                !timedDropId.isNullOrBlank()) {
                                "$accountUserId#$campaignId#$timedDropId"
                            } else null
                            val locallyAccepted = !claimId.isNullOrBlank() &&
                                locallyAcceptedDropClaims.contains(claimId)
                            val claimed = serverClaimed || locallyAccepted
                            val requirementLabel = when {
                                isGtaviDvdChest -> "GTA VI daily badge collection · 1 hour on 6 different days"
                                requiredSubs > 0 -> (if (hasBadgeBenefit) "Creator Badge · " else "Subscription Drop · ") +
                                    "$requiredSubs qualifying subscription(s)"
                                required > 0 -> (if (hasBadgeBenefit) "Creator Watchtime Badge · " else "Watchtime Drop · ") +
                                    "$required minutes"
                                else -> if (hasBadgeBenefit) "Creator Badge" else "Drop reward"
                            }
                            card.addView(View(context).apply {
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                                ).apply { topMargin = dp(10); bottomMargin = dp(8) }
                                setBackgroundColor(0x337F7F7F)
                            })
                            card.addView(TextView(context).apply {
                                text = requirementLabel
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                                setPadding(0, dp(2), 0, dp(2))
                            })
                            val rewardImageUrl = run {
                                val firstBenefit = benefitAt(0)
                                firstBenefit?.optString("imageAssetURL")
                                    ?.takeIf { it.startsWith("https://", true) }
                                    ?: firstBenefit?.optString("imageURL")
                                        ?.takeIf { it.startsWith("https://", true) }
                                    ?: firstBenefit?.optString("imageUrl")
                                        ?.takeIf { it.startsWith("https://", true) }
                            }
                            val rewardRow = LinearLayout(context).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = android.view.Gravity.CENTER_VERTICAL
                            }
                            if (!rewardImageUrl.isNullOrBlank()) {
                                val rewardImage = android.widget.ImageView(context).apply {
                                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                                    contentDescription = displayRewardName
                                }
                                context.imageLoader.enqueue(
                                    ImageRequest.Builder(context).apply {
                                        data(rewardImageUrl)
                                        crossfade(true)
                                        target(rewardImage)
                                    }.build()
                                )
                                rewardRow.addView(rewardImage)
                            }
                            rewardRow.addView(TextView(context).apply {
                                text = displayRewardName
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                                setTypeface(typeface, android.graphics.Typeface.BOLD)
                                setPadding(if (rewardImageUrl.isNullOrBlank()) 0 else dp(10), 0, 0, 0)
                            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                            card.addView(rewardRow)
                            val progress = if (required > 0) {
                                ((watched.toFloat() / required.toFloat()) * 100f).toInt().coerceIn(0, 100)
                            } else 0
                            val progressText = when {
                                isGtaviDvdChest && serverClaimed -> "✓ Daily badge claim confirmed by Twitch"
                                isGtaviDvdChest && locallyAccepted -> "✓ Claim accepted · verify in Twitch Drops Inventory"
                                isGtaviDvdChest && required > 0 && watched >= required &&
                                    !claimId.isNullOrBlank() ->
                                    "Daily chest ready · Use the Claim reward button below"
                                isGtaviDvdChest && required > 0 && watched >= required ->
                                    "Today's watchtime requirement met · Check Twitch Drops & Rewards for the badge"
                                isGtaviDvdChest && required > 0 ->
                                    "$watched / $required minutes today · " + (required - watched) + " min left"
                                hasBadgeBenefit && claimed -> "✓ Badge awarded by Twitch"
                                hasBadgeBenefit && required > 0 && watched >= required ->
                                    "Requirement met · Twitch should award this badge automatically"
                                hasBadgeBenefit && required > 0 ->
                                    "$watched / $required minutes · " + (required - watched) + " min left"
                                hasBadgeBenefit ->
                                    "Badge is awarded automatically when Twitch confirms the qualifying action"
                                claimed -> "Claimed"
                                required > 0 && watched >= required -> "Ready to claim"
                                required > 0 -> "$watched / $required minutes · " + (required - watched) + " min left"
                                else -> "Reward available through this campaign"
                            }
                            card.addView(TextView(context).apply {
                                text = progressText
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                setPadding(0, dp(5), 0, dp(5))
                            })
                            if (required > 0) {
                                card.addView(android.widget.ProgressBar(
                                    context, null, android.R.attr.progressBarStyleHorizontal
                                ).apply {
                                    max = 100
                                    this.progress = if (claimed) 100 else progress
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, dp(6)
                                    )
                                })
                            }
                            val requirementMet = required > 0 && watched >= required
                            if ((!hasBadgeBenefit || isGtaviDvdChest) && !claimed && requirementMet && !claimId.isNullOrBlank()) {
                                card.addView(android.widget.Button(context).apply {
                                    text = "Claim reward"
                                    isAllCaps = false
                                    setOnClickListener {
                                        isEnabled = false
                                        text = "Claiming…"
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            val claimResult = runCatching {
                                                app.xtraModule.graphQLRepository.claimDropReward(
                                                    networkLibrary, headers, claimId
                                                )
                                            }
                                            if (!isAdded || !dialog.isShowing) return@launch
                                            val claimStatus = claimResult.getOrNull()?.let { response ->
                                                runCatching {
                                                    JSONObject(response).optJSONObject("data")
                                                        ?.optJSONObject("claimDropRewards")?.optString("status")
                                                }.getOrNull()
                                            }
                                            if (claimStatus == "ELIGIBLE_FOR_ALL" ||
                                                claimStatus == "DROP_INSTANCE_ALREADY_CLAIMED") {
                                                locallyAcceptedDropClaims.add(claimId)
                                                while (locallyAcceptedDropClaims.size > 100) {
                                                    locallyAcceptedDropClaims.remove(locallyAcceptedDropClaims.first())
                                                }
                                                context.prefs().edit()
                                                    .putStringSet(acceptedClaimsPrefsKey, locallyAcceptedDropClaims.toSet())
                                                    .apply()
                                                Toast.makeText(
                                                    context,
                                                    if (claimStatus == "DROP_INSTANCE_ALREADY_CLAIMED")
                                                        "Twitch reports this reward was already claimed."
                                                    else "Twitch accepted the claim. Checking inventory…",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                refreshDrops()
                                            } else {
                                                isEnabled = true
                                                text = "Claim reward"
                                                Toast.makeText(context, "Twitch could not confirm the claim. Check your Drops Inventory.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                })
                            } else if (claimed) {
                                card.addView(TextView(context).apply {
                                    text = "✓ Reward claimed"
                                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                    setPadding(0, dp(5), 0, 0)
                                })
                            }
                        }
                    }
                    if (!linked && !isGtaviCelebration) {
                        val linkUrl = campaign.optString("accountLinkURL")
                        if (linkUrl.startsWith("https://", true)) {
                            card.addView(android.widget.Button(context).apply {
                                text = "Link game account"
                                isAllCaps = false
                                setOnClickListener {
                                    runCatching {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)))
                                    }.onFailure {
                                        Toast.makeText(context, "Unable to open the account-link page.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            })
                        } else {
                            card.addView(TextView(context).apply {
                                text = "Game account not linked"
                                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                                setPadding(0, dp(8), 0, 0)
                            })
                        }
                    } else {
                        card.addView(TextView(context).apply {
                            text = "✓ Game account linked"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                            setPadding(0, dp(8), 0, 0)
                        })
                    }
                    campaignList.addView(card, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) })
                }
            }
        }

        refreshButton.setOnClickListener { refreshDrops() }
        refreshDrops()
    }

    private fun sendMessage(replyId: String? = null): Boolean {
        with(binding) {
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editText.windowToken, 0)
            editText.clearFocus()
            toggleEmoteMenu(false)
            replyView.visibility = View.GONE
            send.setOnClickListener { sendMessage() }
            editText.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                    sendMessage()
                } else {
                    false
                }
            }
            val text = editText.text.trim()
            editText.text.clear()
            return if (text.isNotEmpty()) {
                viewModel.send(
                    message = text,
                    replyId = replyId,
                    networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                    helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext()),
                    accountId = requireContext().tokenPrefs().getString(C.USER_ID, null),
                    channelId = requireArguments().getString(KEY_CHANNEL_ID),
                    channelLogin = requireArguments().getString(KEY_CHANNEL_LOGIN),
                    useApiCommands = requireContext().prefs().getBoolean(C.DEBUG_API_COMMANDS, true),
                    useApiChatMessages = requireContext().prefs().getBoolean(C.DEBUG_API_CHAT_MESSAGES, true),
                    enableIntegrity = requireContext().prefs().getBoolean(C.ENABLE_INTEGRITY, false),
                )
                val lastIndex = synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.lastIndex
                }
                isFollowingChat = true
                btnDown.isGone = true
                recyclerView.scrollToPosition(lastIndex)
                true
            } else {
                false
            }
        }
    }

    override fun onCreateMessageClickedChatAdapter(): MessageClickedChatAdapter? {
        return adapter?.createMessageClickedChatAdapter()
    }

    override fun onCreateReplyClickedChatAdapter(): ReplyClickedChatAdapter? {
        return adapter?.createReplyClickedChatAdapter()
    }

    override fun onReplyClicked(replyId: String?, userLogin: String?, userName: String?, message: String?) {
        with(binding) {
            if (!replyId.isNullOrBlank()) {
                messageDialog?.dismiss()
                replyView.visibility = View.VISIBLE
                replyText.text = message?.let {
                    val name = if (userName != null && userLogin != null && !userLogin.equals(userName, true)) {
                        when (requireContext().prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${userName}(${userLogin})"
                            "1" -> userName
                            else -> userLogin
                        }
                    } else {
                        userName ?: userLogin
                    }
                    getString(R.string.replying_to_message, name, message)
                }
                replyClose.setOnClickListener {
                    replyView.visibility = View.GONE
                    send.setOnClickListener { sendMessage() }
                    editText.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                            sendMessage()
                        } else {
                            false
                        }
                    }
                }
                send.setOnClickListener { sendMessage(replyId) }
                editText.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                        sendMessage(replyId)
                    } else {
                        false
                    }
                }
            }
            editText.apply {
                requestFocus()
                WindowCompat.getInsetsController(this@ChatFragment.requireActivity().window, this).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    override fun onCopyMessageClicked(message: String) {
        binding.editText.setText(message)
    }

    override fun onViewProfileClicked(id: String?, login: String?, name: String?, channelImage: String?) {
        findNavController().navigate(
            ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                channelId = id,
                channelLogin = login,
                channelName = name,
                channelImage = channelImage
            )
        )
        (parentFragment as? PlayerFragment)?.minimize()
    }

    override fun onTranslateMessageClicked(chatMessage: ChatMessage, languageTag: String?) {
        val message = chatMessage.message ?: chatMessage.systemMsg
        if (message != null) {
            if (languageTag != null) {
                translateMessage(message, chatMessage, languageTag)
            } else {
                val languageIdentifier = languageIdentifier ?: LanguageIdentification.getClient().also { languageIdentifier = it }
                languageIdentifier.identifyLanguage(message)
                    .addOnSuccessListener { tag ->
                        translateMessage(message, chatMessage, tag)
                    }
                    .addOnFailureListener {
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translate_failed_id)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = null
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
            }
        }
    }

    private fun translateMessage(message: String, chatMessage: ChatMessage, tag: String) {
        val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
        if (tag != "und" && tag != targetLanguage) {
            TranslateLanguage.fromLanguageTag(tag)?.let { sourceLanguage ->
                val translator = translators[sourceLanguage] ?: Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).also {
                    if (translators.size >= 3) {
                        val entry = translators.entries.first()
                        translators.remove(entry.key)
                        entry.value.close()
                    }
                    translators[sourceLanguage] = it
                }
                translator.translate(message)
                    .addOnSuccessListener { text ->
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translated_message, languageName, text)
                        chatMessage.translationFailed = false
                        chatMessage.messageLanguage = null
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
                    .addOnFailureListener {
                        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                        val previousTranslation = chatMessage.translatedMessage
                        chatMessage.translatedMessage = getString(R.string.translate_failed, languageName)
                        chatMessage.translationFailed = true
                        chatMessage.messageLanguage = sourceLanguage
                        adapter?.let { adapter ->
                            synchronized(viewModel.chatMessages) {
                                viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                            }?.let {
                                (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                    adapter.updateTranslation(chatMessage, it, previousTranslation)
                                } ?: adapter.notifyItemChanged(it)
                            }
                        }
                        messageDialog?.updateTranslation(chatMessage, previousTranslation)
                        replyDialog?.updateTranslation(chatMessage, previousTranslation)
                    }
            }
        } else {
            val previousTranslation = chatMessage.translatedMessage
            chatMessage.translatedMessage = getString(R.string.translate_failed_id)
            chatMessage.translationFailed = true
            chatMessage.messageLanguage = null
            adapter?.let { adapter ->
                synchronized(viewModel.chatMessages) {
                    viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                }?.let {
                    (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                        adapter.updateTranslation(chatMessage, it, previousTranslation)
                    } ?: adapter.notifyItemChanged(it)
                }
            }
            messageDialog?.updateTranslation(chatMessage, previousTranslation)
            replyDialog?.updateTranslation(chatMessage, previousTranslation)
        }
    }

    private fun showLanguageDownloadDialog(chatMessage: ChatMessage, sourceLanguage: String) {
        val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
        requireContext().getAlertDialogBuilder()
            .setMessage(getString(R.string.download_language_model_message, languageName))
            .setNegativeButton(getString(R.string.no), null)
            .setPositiveButton(getString(R.string.yes)) { _, _ ->
                val targetLanguage = requireContext().prefs().getString(C.CHAT_TRANSLATE_TARGET, "en") ?: "en"
                val translator = translators[sourceLanguage] ?: Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLanguage)
                        .setTargetLanguage(targetLanguage)
                        .build()
                ).also {
                    if (translators.size >= 3) {
                        val entry = translators.entries.first()
                        translators.remove(entry.key)
                        entry.value.close()
                    }
                    translators[sourceLanguage] = it
                }
                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        val message = chatMessage.message ?: chatMessage.systemMsg
                        if (message != null) {
                            translator.translate(message)
                                .addOnSuccessListener { text ->
                                    val languageName = Locale.forLanguageTag(sourceLanguage).displayLanguage
                                    val previousTranslation = chatMessage.translatedMessage
                                    chatMessage.translatedMessage = getString(R.string.translated_message, languageName, text)
                                    chatMessage.translationFailed = false
                                    chatMessage.messageLanguage = null
                                    adapter?.let { adapter ->
                                        synchronized(viewModel.chatMessages) {
                                            viewModel.chatMessages.indexOf(chatMessage).takeIf { it != -1 }
                                        }?.let {
                                            (binding.recyclerView.layoutManager?.findViewByPosition(it) as? TextView)?.let {
                                                adapter.updateTranslation(chatMessage, it, previousTranslation)
                                            } ?: adapter.notifyItemChanged(it)
                                        }
                                    }
                                    messageDialog?.updateTranslation(chatMessage, previousTranslation)
                                    replyDialog?.updateTranslation(chatMessage, previousTranslation)
                                }
                        }
                    }
            }
            .show()
    }

    override fun onNetworkRestored() {
        if (isResumed) {
            val args = requireArguments()
            val channelId = args.getString(KEY_CHANNEL_ID)
            val channelLogin = args.getString(KEY_CHANNEL_LOGIN)
            if (args.getBoolean(KEY_IS_LIVE)) {
                viewModel.resumeLive(channelId, channelLogin)
            } else {
                viewModel.resumeReplay(
                    channelId = channelId,
                    channelLogin = channelLogin,
                    chatUrl = args.getString(KEY_CHAT_URL),
                    videoId = args.getString(KEY_VIDEO_ID),
                    createdAt = args.getString(KEY_CREATED_AT),
                    startTime = args.getInt(KEY_START_TIME),
                    getCurrentPosition = (parentFragment as PlayerFragment)::getCurrentPosition,
                    getCurrentSpeed = (parentFragment as PlayerFragment)::getCurrentSpeed
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val keepForBackgroundAudio = (parentFragment as? PlayerFragment)?.shouldKeepChatAliveInBackground() == true
        if (!isMergedRendererOnly && (!requireArguments().getBoolean(KEY_IS_LIVE) || (!keepForBackgroundAudio && !requireContext().prefs().getBoolean(C.PLAYER_KEEP_CHAT_OPEN, false)))) {
            viewModel.stopLiveChat()
            viewModel.stopReplayChat()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        languageIdentifier?.close()
        translators.forEach {
            it.value.close()
        }
    }

    class SpaceTokenizer : MultiAutoCompleteTextView.Tokenizer {

        override fun findTokenStart(text: CharSequence, cursor: Int): Int {
            var i = cursor

            while (i > 0 && text[i - 1] != ' ') {
                i--
            }
            while (i < cursor && text[i] == ' ') {
                i++
            }

            return i
        }

        override fun findTokenEnd(text: CharSequence, cursor: Int): Int {
            var i = cursor
            val len = text.length

            while (i < len) {
                if (text[i] == ' ') {
                    return i
                } else {
                    i++
                }
            }

            return len
        }

        override fun terminateToken(text: CharSequence): CharSequence {
            return "${if (text.startsWith(':')) text.substring(1) else text} "
        }
    }

    companion object {
        private const val KEY_IS_LIVE = "isLive"
        private const val KEY_RENDERER_ONLY = "rendererOnly"
        private const val KEY_CHANNEL_ID = "channel_id"
        private const val KEY_CHANNEL_LOGIN = "channel_login"
        private const val KEY_CHANNEL_NAME = "channel_name"
        private const val KEY_STREAM_ID = "streamId"
        private const val KEY_VIDEO_ID = "videoId"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_CHAT_URL = "chatUrl"
        private const val KEY_START_TIME = "startTime"

        fun newInstance(channelId: String?, channelLogin: String?, channelName: String?, streamId: String?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, true)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CHANNEL_NAME, channelName)
                    putString(KEY_STREAM_ID, streamId)
                }
            }
        }

        fun newRendererOnlyInstance(channelId: String?, channelLogin: String?, channelName: String?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, true)
                    putBoolean(KEY_RENDERER_ONLY, true)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CHANNEL_NAME, channelName)
                }
            }
        }

        fun newInstance(channelId: String?, channelLogin: String?, videoId: String?, createdAt: String?, startTime: Int?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(KEY_IS_LIVE, false)
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_VIDEO_ID, videoId)
                    putString(KEY_CREATED_AT, createdAt)
                    putInt(KEY_START_TIME, (startTime ?: -1))
                }
            }
        }

        fun newLocalInstance(channelId: String?, channelLogin: String?, createdAt: String?, chatUrl: String?): ChatFragment {
            return ChatFragment().apply {
                arguments = Bundle().apply {
                    putString(KEY_CHANNEL_ID, channelId)
                    putString(KEY_CHANNEL_LOGIN, channelLogin)
                    putString(KEY_CREATED_AT, createdAt)
                    putString(KEY_CHAT_URL, chatUrl)
                }
            }
        }
    }
}