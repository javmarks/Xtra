package com.github.andreyasadchy.xtra.ui.search.channels

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.CommonRecyclerViewLayoutBinding
import com.github.andreyasadchy.xtra.databinding.FragmentSearchChannelsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.common.PagedListFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.search.Searchable
import com.github.andreyasadchy.xtra.ui.search.channels.ChannelSearchViewModel.Companion.ChannelSearchViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AddChatDialogFragment : DialogFragment() {
    interface Listener { fun onChannelSelected(user: User) }

    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val searchView = SearchView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isIconified = false
            queryHint = "Search channels"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }
        val closeButton = ImageButton(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                (48 * resources.displayMetrics.density).toInt(),
                (48 * resources.displayMetrics.density).toInt()
            )
            setImageResource(R.drawable.baseline_close_black_36)
            contentDescription = "Close"
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { dismiss() }
        }
        header.addView(searchView)
        header.addView(closeButton)
        root.addView(header)
        val searchContainer = FrameLayout(context).apply {
            id = R.id.chatFragmentContainer
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(searchContainer)

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchJob?.cancel()
                (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) as? AddChatChannelSearchFragment)?.search(query)
                return true
            }
            override fun onQueryTextChange(newText: String): Boolean {
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(500)
                    (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) as? AddChatChannelSearchFragment)?.search(newText)
                }
                return true
            }
        })
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.chatFragmentContainer) == null) {
            childFragmentManager.beginTransaction()
                .replace(R.id.chatFragmentContainer, AddChatChannelSearchFragment())
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.85f).toInt())
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    fun onChannelPicked(user: User) {
        (parentFragment as? Listener)?.onChannelSelected(user)
        dismiss()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        super.onDestroyView()
    }
}

class AddChatChannelSearchFragment : PagedListFragment(), Searchable {
    private var _binding: CommonRecyclerViewLayoutBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChannelSearchViewModel by viewModels { ChannelSearchViewModelFactory }
    private lateinit var pagingAdapter: PagingDataAdapter<User, out RecyclerView.ViewHolder>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = CommonRecyclerViewLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pagingAdapter = AddChatChannelSearchAdapter(this) { user ->
            (parentFragment as? AddChatDialogFragment)?.onChannelPicked(user)
        }
        setAdapter(binding.recyclerView, pagingAdapter)
        binding.swipeRefresh.isEnabled = true
        binding.swipeRefresh.setOnRefreshListener { pagingAdapter.refresh() }
    }

    override fun initialize() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.flow.collectLatest { pagingData -> pagingAdapter.submitData(pagingData) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                pagingAdapter.loadStateFlow.collectLatest { loadState ->
                    binding.progressBar.visibility = if (loadState.refresh is LoadState.Loading && pagingAdapter.itemCount == 0) View.VISIBLE else View.GONE
                    binding.swipeRefresh.isRefreshing = loadState.refresh is LoadState.Loading && pagingAdapter.itemCount != 0
                    binding.nothingHere.visibility = if (loadState.refresh !is LoadState.Loading && pagingAdapter.itemCount == 0 && viewModel.query.value.isNotBlank()) View.VISIBLE else View.GONE
                    if ((loadState.refresh as? LoadState.Error ?: loadState.append as? LoadState.Error ?: loadState.prepend as? LoadState.Error)?.error?.message == C.FAILED_INTEGRITY_CHECK) {
                        (requireActivity() as? MainActivity)?.getNewIntegrityToken("refresh", childFragmentManager)
                    }
                }
            }
        }
    }

    override fun search(query: String) {
        viewModel.setQuery(query)
        viewModel.saveRecentSearch(query)
    }

    override fun onNetworkRestored() { pagingAdapter.retry() }

    override fun onIntegrityTokenLoaded(callback: String?) {
        if (callback == "refresh") pagingAdapter.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

private class AddChatChannelSearchAdapter(
    private val fragment: androidx.fragment.app.Fragment,
    private val onSelected: (User) -> Unit,
) : PagingDataAdapter<User, AddChatChannelSearchAdapter.PagingViewHolder>(
    object : DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: User, newItem: User) = true
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PagingViewHolder {
        val binding = FragmentSearchChannelsListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PagingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PagingViewHolder, position: Int) { holder.bind(getItem(position)) }

    inner class PagingViewHolder(private val binding: FragmentSearchChannelsListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: User?) {
            if (item == null) return
            with(binding) {
                val context = fragment.requireContext()
                root.setOnClickListener { onSelected(item) }
                if (item.profileImage != null) {
                    userImage.visibility = View.VISIBLE
                    context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                        data(item.profileImage)
                        if (context.prefs().getBoolean(C.UI_ROUND_USER_IMAGE, true)) transformations(CircleCropTransformation())
                        crossfade(true)
                        target(userImage)
                    }.build())
                } else userImage.visibility = View.GONE
                if (item.name != null) {
                    userName.visibility = View.VISIBLE
                    userName.text = if (item.login != null && !item.login.equals(item.name, true)) {
                        when (context.prefs().getString(C.UI_NAME_DISPLAY, "0")) {
                            "0" -> "${item.name}(${item.login})"
                            "1" -> item.name
                            else -> item.login
                        }
                    } else item.name
                } else userName.visibility = View.GONE
                if (item.followerCount != null) {
                    userFollowers.visibility = View.VISIBLE
                    userFollowers.text = context.resources.getQuantityString(R.plurals.followers, item.followerCount,
                        TwitchApiHelper.formatCount(item.followerCount, context.prefs().getBoolean(C.UI_TRUNCATE_VIEW_COUNT, true)))
                } else userFollowers.visibility = View.GONE
                if (item.isLive == true) {
                    typeText.visibility = View.VISIBLE
                    typeText.text = context.getString(R.string.live)
                } else typeText.visibility = View.GONE
            }
        }
    }
}
