package com.pawchive.ui.creator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.pawchive.common.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.data.repository.CreatorSubscriptionRepository
import com.pawchive.common.databinding.FragmentCreatorProfileBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.core.store.SettingsManager
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.core.error.ErrorMessageHelper
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CreatorProfileFragment : Fragment() {

    private var _binding: FragmentCreatorProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CreatorProfileViewModel by viewModels()

    private lateinit var postAdapter: PostAdapter
    @Inject
    lateinit var bookmarkManager: BookmarkManager
    @Inject
    lateinit var blockedCreatorManager: BlockedCreatorManager
    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var subscriptionRepository: CreatorSubscriptionRepository
    @Inject lateinit var settingsManager: SettingsManager

    private var service: String = ""
    private var creatorId: String = ""

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private var currentSort = PostSortOption.NEWEST_PUBLISHED
    private var searchQuery: String = ""
    private var isAnnouncementsExpanded = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            service = it.getString(ARG_SERVICE, "")
            creatorId = it.getString(ARG_CREATOR_ID, "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreatorProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener {
            hideKeyboard()
            parentFragmentManager.popBackStack()
        }

        setupRecyclerView()
        setupBookmarkButton()
        setupSubscribeButton()
        setupBlockButton()
        setupLoadMoreButton()
        setupSortButton()
        setupSearchView()
        setupAnnouncementsToggle()
        observeCreatorState()

        viewModel.loadCreator(service, creatorId)
    }

    private fun observeCreatorState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE

                    if (state.errorMessage != null && state.posts.isEmpty()) {
                        Toast.makeText(
                            context,
                            ErrorMessageHelper.getFriendlyMessage(context, state.errorMessage),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    // 更新创作者信�?
                    binding.tvCreatorTitle.text = state.name.ifEmpty { creatorId }
                    binding.tvCreatorService.text = service.uppercase()
                    setServiceLabelColor(service)
                    if (state.name.isNotEmpty()) {
                        CreatorNameCache.cacheCreatorName(service, creatorId, state.name)
                    }
                    loadAvatar()

                    // 更新公告
                    binding.layoutAnnouncements.removeAllViews()
                    if (state.announcements.isNotEmpty()) {
                        binding.layoutAnnouncementsHeader.visibility = View.VISIBLE
                        updateAnnouncementsVisibility()
                        for (announcement in state.announcements) {
                            val textView = TextView(requireContext()).apply {
                                text = Html.fromHtml(announcement.content ?: "", Html.FROM_HTML_MODE_COMPACT)
                                setTextColor(requireContext().getColor(R.color.text_secondary))
                                textSize = 13f
                                background = requireContext().getDrawable(R.drawable.comment_bg)
                                setPadding(12, 12, 12, 12)
                            }
                            binding.layoutAnnouncements.addView(textView)
                        }
                    } else {
                        binding.layoutAnnouncementsHeader.visibility = View.GONE
                    }

                    // 更新关联链接
                    binding.layoutLinks.removeAllViews()
                    if (state.links.isNotEmpty()) {
                        binding.tvLinksHeader.visibility = View.VISIBLE
                        for (link in state.links) {
                            val textView = TextView(requireContext()).apply {
                                text = "${link.name} (${link.service})"
                                setTextColor(resources.getColor(R.color.primary_light, null))
                                textSize = 14f
                                setPadding(0, 8, 0, 8)
                                setOnClickListener {
                                    (activity as? AppNavigator)?.openCreatorProfile(link.service, link.id)
                                }
                            }
                            binding.layoutLinks.addView(textView)
                        }
                    } else {
                        binding.tvLinksHeader.visibility = View.GONE
                    }

                    // 更新帖子列表
                    applySort(state.posts)
                    updateLoadMoreButton(state.hasMore)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        postAdapter = PostAdapter(
            posts = emptyList(),
            bookmarkManager = bookmarkManager,
            authRepository = authRepository,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            onPostClicked = { post ->
                (activity as? AppNavigator)?.openPostDetail(post.service, post.user, post.id)
            },
            onCreatorClicked = { _, _ -> },
            onBookmarkChanged = { _, _ -> },
            onLoadMore = {},
            showCreatorInfo = false
        )
        binding.rvCreatorPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCreatorPosts.adapter = postAdapter
    }

    private fun setupLoadMoreButton() {
        binding.btnLoadMore.setOnClickListener {
            viewModel.loadMorePosts()
        }
    }

    private fun setupSortButton() {
        binding.btnSort.text = getString(currentSort.displayNameRes)
        binding.btnSort.setOnClickListener {
            hideKeyboard()
            showSortDialog()
        }
    }

    private fun setupSearchView() {
        val focusAndShow = {
            binding.searchViewCreator.apply {
                isIconified = false
                requestFocus()
                requestFocusFromTouch()
            }
        }
        binding.searchViewCreator.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                hideKeyboard()
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText.orEmpty().trim()
                applySort(viewModel.uiState.value.posts)
                return true
            }
        })
        binding.searchCardCreator.setOnClickListener { focusAndShow() }
        binding.searchViewCreator.setOnClickListener { focusAndShow() }
        binding.searchViewCreator.setOnTouchListener { view, event ->
            focusAndShow()
            false
        }
    }

    private fun setupAnnouncementsToggle() {
        binding.layoutAnnouncementsHeader.setOnClickListener {
            isAnnouncementsExpanded = !isAnnouncementsExpanded
            updateAnnouncementsVisibility()
        }
    }

    private fun updateAnnouncementsVisibility() {
        binding.layoutAnnouncements.visibility = if (isAnnouncementsExpanded) View.VISIBLE else View.GONE
        val chevronRes = if (isAnnouncementsExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        binding.icAnnouncementsChevron.setImageResource(chevronRes)
        val contentDesc = if (isAnnouncementsExpanded) getString(R.string.collapse) else getString(R.string.expand)
        binding.icAnnouncementsChevron.contentDescription = contentDesc
    }

    private fun showSortDialog() {
        val options = PostSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
        val currentIndex = PostSortOption.values().indexOf(currentSort)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentSort = PostSortOption.values()[which]
                binding.btnSort.text = getString(currentSort.displayNameRes)
                applySort(viewModel.uiState.value.posts)
                dialog.dismiss()
            }
            .show()
    }

    private fun applySort(posts: List<com.pawchive.core.model.Post>) {
        if (posts.isEmpty()) return
        val filtered = if (searchQuery.isNotEmpty()) {
            posts.filter {
                it.title?.contains(searchQuery, ignoreCase = true) == true ||
                it.content?.contains(searchQuery, ignoreCase = true) == true
            }
        } else {
            posts
        }
        val sorted = when (currentSort) {
            PostSortOption.NEWEST_PUBLISHED -> filtered.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> filtered.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> filtered.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> filtered.sortedBy { it.edited ?: it.published }
        }
        postAdapter.updatePosts(sorted)
    }

    private fun setupBookmarkButton() {
        val isBookmarked = bookmarkManager.isCreatorBookmarked(service, creatorId)
        updateBookmarkIcon(isBookmarked)

        binding.btnCreatorBookmark.setOnClickListener {
            hideKeyboard()
            val newStatus = !bookmarkManager.isCreatorBookmarked(service, creatorId)
            if (newStatus) {
                bookmarkManager.bookmarkCreator(service, creatorId)
                if (authRepository.isLoggedIn()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = authRepository.addCreatorToFavorites(service, creatorId)
                        if (result.isFailure) {
                            bookmarkManager.unbookmarkCreator(service, creatorId)
                            updateBookmarkIcon(false)
                            Toast.makeText(
                                context,
                                getString(R.string.connection_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            // 云端收藏确认成功 → 收藏即订阅联动
                            autoSubscribeIfEnabled()
                        }
                    }
                } else {
                    // 未登录：本地收藏即确认成功 → 收藏即订阅联动
                    autoSubscribeIfEnabled()
                }
            } else {
                bookmarkManager.unbookmarkCreator(service, creatorId)
                if (authRepository.isLoggedIn()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val result = authRepository.removeCreatorFromFavorites(service, creatorId)
                        if (result.isFailure) {
                            bookmarkManager.bookmarkCreator(service, creatorId)
                            updateBookmarkIcon(true)
                            Toast.makeText(
                                context,
                                getString(R.string.connection_error),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            updateBookmarkIcon(newStatus)
            Toast.makeText(
                context,
                if (newStatus) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 收藏即订阅联动（ARCH-FEATURE-003 遗留项）。
     * - 仅在"收藏创作者时自动订阅"开关开启且尚未订阅时触发
     * - 以当前已加载的最新帖为基线（同铃铛订阅语义），避免历史帖当新帖通知
     * - 取消收藏不会自动退订（退订是用户显式意图，可在订阅管理页操作）
     */
    private fun autoSubscribeIfEnabled() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (!settingsManager.isAutoSubscribeOnBookmarkEnabled()) return@launch
            if (subscriptionRepository.isSubscribed(service, creatorId)) return@launch
            val newestPostId = viewModel.uiState.value.posts.firstOrNull()?.id
            subscriptionRepository.subscribe(
                service = service,
                creatorId = creatorId,
                name = viewModel.uiState.value.name.ifEmpty { creatorId },
                lastPostId = newestPostId
            )
            updateSubscribeIcon(true)
            Toast.makeText(context, R.string.auto_subscribed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateBookmarkIcon(isBookmarked: Boolean) {
        binding.btnCreatorBookmark.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
        )
    }

    /**
     * 内容更新订阅按钮（ARCH-FEATURE-003）。
     * - 订阅时以当前已加载的最新帖为基线（lastPostId），避免把历史帖当新帖通知
     * - 订阅状态为本地存储，不依赖登录态
     * - Android 13+ 首次订阅时请求通知权限：无论授权与否都继续订阅
     *   （站内未读徽标不依赖通知权限，仅系统栏推送需要授权）
     */
    private fun setupSubscribeButton() {
        viewLifecycleOwner.lifecycleScope.launch {
            updateSubscribeIcon(subscriptionRepository.isSubscribed(service, creatorId))
        }

        binding.btnCreatorSubscribe.setOnClickListener {
            hideKeyboard()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewLifecycleOwner.lifecycleScope.launch { toggleSubscription() }
            }
        }
    }

    /** 通知权限请求（订阅新帖推送，ARCH-FEATURE-003 系统通知栏推送）。 */
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 无论授权与否都继续订阅：拒绝权限仅影响系统栏推送，站内未读徽标不受影响
        viewLifecycleOwner.lifecycleScope.launch { toggleSubscription() }
    }

    /** 订阅 / 退订切换（在 lifecycleScope 中调用）。 */
    private suspend fun toggleSubscription() {
        val subscribed = subscriptionRepository.isSubscribed(service, creatorId)
        if (subscribed) {
            subscriptionRepository.unsubscribe(service, creatorId)
            updateSubscribeIcon(false)
            Toast.makeText(context, R.string.subscription_removed, Toast.LENGTH_SHORT).show()
        } else {
            val newestPostId = viewModel.uiState.value.posts.firstOrNull()?.id
            subscriptionRepository.subscribe(
                service = service,
                creatorId = creatorId,
                name = viewModel.uiState.value.name.ifEmpty { creatorId },
                lastPostId = newestPostId
            )
            updateSubscribeIcon(true)
            Toast.makeText(context, R.string.subscription_added, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSubscribeIcon(subscribed: Boolean) {
        val tintRes = if (subscribed) {
            com.google.android.material.R.attr.colorPrimary
        } else {
            com.google.android.material.R.attr.colorOnSurface
        }
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(tintRes, typedValue, true)
        binding.btnCreatorSubscribe.imageTintList =
            android.content.res.ColorStateList.valueOf(typedValue.data)
        binding.btnCreatorSubscribe.contentDescription = getString(
            if (subscribed) R.string.unsubscribe_from_updates else R.string.subscribe_to_updates
        )
    }

    private fun setupBlockButton() {
        val isBlocked = blockedCreatorManager.isCreatorBlocked(service, creatorId)
        updateBlockButtonState(isBlocked)

        binding.btnBlockCreator.setOnClickListener {
            hideKeyboard()
            val newBlocked = !blockedCreatorManager.isCreatorBlocked(service, creatorId)
            if (newBlocked) {
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.block_creator_confirm_title)
                    .setMessage(getString(R.string.block_creator_confirm_message))
                    .setPositiveButton(R.string.block_creator) { _, _ ->
                        blockedCreatorManager.blockCreator(service, creatorId)
                        updateBlockButtonState(true)
                        Toast.makeText(context, R.string.creator_blocked, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                blockedCreatorManager.unblockCreator(service, creatorId)
                updateBlockButtonState(false)
                Toast.makeText(context, R.string.creator_unblocked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBlockButtonState(isBlocked: Boolean) {
        binding.btnBlockCreator.text = getString(
            if (isBlocked) R.string.unblock_creator else R.string.block_creator
        )
        binding.btnBlockCreator.setIconResource(
            if (isBlocked) R.drawable.ic_shield_off else R.drawable.ic_shield
        )
    }

    private fun setServiceLabelColor(serviceName: String) {
        val context = requireContext()
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colorRes = when (serviceName.lowercase()) {
            "patreon" -> if (isDarkMode) R.color.patreon_text_dark else R.color.patreon_text_light
            "fanbox" -> if (isDarkMode) R.color.fanbox_text_dark else R.color.fanbox_text_light
            else -> if (isDarkMode) R.color.service_text_default_dark else R.color.service_text_default_light
        }
        binding.tvCreatorService.setTextColor(context.getColor(colorRes))
    }

    private fun loadAvatar() {
        val avatarUrl = "https://pawchive.pw/icons/$service/$creatorId"
        binding.ivCreatorAvatar.load(avatarUrl) {
            crossfade(true)
            placeholder(R.drawable.ic_image)
            error(R.drawable.ic_image_off)
        }
    }

    private fun updateLoadMoreButton(hasMore: Boolean) {
        binding.btnLoadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        binding.searchViewCreator.clearFocus()
        imm.hideSoftInputFromWindow(binding.searchViewCreator.windowToken, 0)
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SERVICE = "service"
        private const val ARG_CREATOR_ID = "creator_id"

        fun newInstance(service: String, creatorId: String): CreatorProfileFragment {
            return CreatorProfileFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SERVICE, service)
                    putString(ARG_CREATOR_ID, creatorId)
                }
            }
        }
    }
}
