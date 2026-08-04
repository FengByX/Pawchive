package com.pawchive.ui.creator

import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.pawchive.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.FragmentCreatorProfileBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.post.PostDetailFragment
import com.pawchive.utils.ErrorMessageHelper
import kotlinx.coroutines.launch

class CreatorProfileFragment : Fragment() {

    private var _binding: FragmentCreatorProfileBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CreatorProfileViewModel by viewModels()

    private lateinit var postAdapter: PostAdapter
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var blockedCreatorManager: BlockedCreatorManager
    private lateinit var authRepository: AuthRepository

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
        bookmarkManager = BookmarkManager.getInstance(requireContext())
        blockedCreatorManager = BlockedCreatorManager.getInstance(requireContext())
        authRepository = AuthRepository(requireContext())

        binding.btnBack.setOnClickListener {
            hideKeyboard()
            parentFragmentManager.popBackStack()
        }

        setupRecyclerView()
        setupBookmarkButton()
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
                                    val creatorFragment = newInstance(link.service, link.id)
                                    (activity as? MainActivity)?.loadFragment(creatorFragment)
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
                val detailFragment = PostDetailFragment.newInstance(post.service, post.user, post.id)
                (activity as? MainActivity)?.loadFragment(detailFragment)
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

    private fun applySort(posts: List<com.pawchive.data.model.Post>) {
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
            } else {
                bookmarkManager.unbookmarkCreator(service, creatorId)
            }
            updateBookmarkIcon(newStatus)
            Toast.makeText(
                context,
                if (newStatus) getString(R.string.bookmark_added) else getString(R.string.bookmark_removed),
                Toast.LENGTH_SHORT
            ).show()

            if (authRepository.isLoggedIn()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = if (newStatus) {
                        authRepository.addCreatorToFavorites(service, creatorId)
                    } else {
                        authRepository.removeCreatorFromFavorites(service, creatorId)
                    }
                    if (result.isFailure) {
                        val rolledBack = !newStatus
                        if (rolledBack) {
                            bookmarkManager.bookmarkCreator(service, creatorId)
                        } else {
                            bookmarkManager.unbookmarkCreator(service, creatorId)
                        }
                        updateBookmarkIcon(rolledBack)
                        Toast.makeText(
                            context,
                            getString(R.string.connection_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    private fun updateBookmarkIcon(isBookmarked: Boolean) {
        binding.btnCreatorBookmark.setImageResource(
            if (isBookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark_outline
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
