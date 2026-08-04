package com.pawchive.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pawchive.R
import com.pawchive.data.model.Post
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.FragmentHomeBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.creator.CreatorProfileFragment
import com.pawchive.ui.post.PostDetailFragment
import com.pawchive.utils.ErrorMessageHelper
import com.pawchive.utils.ErrorStateViewHelper
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var postAdapter: PostAdapter
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var authRepository: AuthRepository

    private var showBookmarksOnly = false
    private val loadedPosts = mutableListOf<Post>()

    // 内嵌错误页（FEATURE-006）
    private lateinit var errorStateView: ErrorStateViewHelper.Bound

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private var currentSort = PostSortOption.NEWEST_PUBLISHED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            showBookmarksOnly = it.getBoolean(ARG_SHOW_BOOKMARKS, false)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookmarkManager = BookmarkManager.getInstance(requireContext())
        authRepository = AuthRepository(requireContext())

        setupRecyclerView()
        setupSwipeRefresh()
        setupSortButton()
        // 绑定内嵌错误页（FEATURE-006）
        errorStateView = ErrorStateViewHelper.bind(binding.root) {
            viewModel.refresh()
        }

        if (showBookmarksOnly) {
            loadBookmarks(isRefresh = false)
        } else {
            observeHomeState()
            viewModel.loadInitial()
        }
    }

    private fun observeHomeState() {
        viewModel.posts.observe(viewLifecycleOwner) { posts ->
            loadedPosts.clear()
            loadedPosts.addAll(posts)
            if (posts.isEmpty()) {
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.tvEmpty.visibility = View.GONE
                // 有内容时隐藏错误页
                errorStateView.hide()
                applySort()
                viewLifecycleOwner.lifecycleScope.launch {
                    var lastRefreshAt = 0L
                    CreatorNameCache.prefetchCreatorNames(posts) {
                        val now = System.currentTimeMillis()
                        if (now - lastRefreshAt >= 300L) {
                            lastRefreshAt = now
                            _binding?.rvPosts?.post { postAdapter.refreshCreatorNames() }
                        }
                    }
                    postAdapter.refreshCreatorNames()
                }
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressBar.visibility = if (isLoading && !binding.swipeRefresh.isRefreshing) {
                View.VISIBLE
            } else {
                View.GONE
            }
            if (!isLoading) {
                binding.swipeRefresh.isRefreshing = false
            }
        }

        viewModel.errorMessage.observe(viewLifecycleOwner) { message ->
            if (!message.isNullOrBlank()) {
                if (loadedPosts.isEmpty()) {
                    // 列表为空：展示内嵌错误页，提供重试入口（FEATURE-006）
                    binding.tvEmpty.visibility = View.GONE
                    errorStateView.show(message)
                } else {
                    // 已有内容：错误以 Toast 呈现，不打断浏览
                    Toast.makeText(
                        context,
                        ErrorMessageHelper.getFriendlyMessage(context, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                // 错误清除时隐藏错误页
                errorStateView.hide()
            }
        }

        viewModel.hasMore.observe(viewLifecycleOwner) { hasMore ->
            postAdapter.setFooterVisible(hasMore)
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
            onCreatorClicked = { service, creatorId ->
                val creatorFragment = CreatorProfileFragment.newInstance(service, creatorId)
                (activity as? MainActivity)?.loadFragment(creatorFragment)
            },
            onBookmarkChanged = { _, _ ->
                if (showBookmarksOnly) {
                    loadBookmarks()
                }
            },
            onLoadMore = { loadMorePosts() }
        )

        binding.rvPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPosts.adapter = postAdapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            if (showBookmarksOnly) {
                loadBookmarks(isRefresh = true)
            } else {
                viewModel.refresh()
            }
        }
        binding.swipeRefresh.setColorSchemeColors(
            getThemeColor(com.google.android.material.R.attr.colorPrimary)
        )
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun setupSortButton() {
        binding.btnSort.text = getString(currentSort.displayNameRes)
        binding.btnSort.setOnClickListener {
            showSortDialog()
        }
    }

    private fun showSortDialog() {
        val options = PostSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
        val currentIndex = PostSortOption.values().indexOf(currentSort)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentSort = PostSortOption.values()[which]
                binding.btnSort.text = getString(currentSort.displayNameRes)
                applySort()
                dialog.dismiss()
            }
            .show()
    }

    private fun applySort() {
        val postsToSort = if (showBookmarksOnly) {
            bookmarkManager.getBookmarkedPosts()
        } else {
            loadedPosts.toList()
        }

        val sorted = when (currentSort) {
            PostSortOption.NEWEST_PUBLISHED -> postsToSort.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> postsToSort.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> postsToSort.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> postsToSort.sortedBy { it.edited ?: it.published }
        }

        postAdapter.updatePosts(sorted)
    }

    private fun loadMorePosts() {
        if (!showBookmarksOnly) {
            viewModel.loadMore()
        }
    }

    private fun loadBookmarks(isRefresh: Boolean = false) {
        val bookmarkedPosts = bookmarkManager.getBookmarkedPosts()
        // 收藏页不展示网络错误页
        errorStateView.hide()
        if (bookmarkedPosts.isEmpty()) {
            binding.tvEmpty.text = getString(R.string.no_favorites)
            binding.tvEmpty.visibility = View.VISIBLE
            postAdapter.updatePosts(emptyList())
        } else {
            binding.tvEmpty.visibility = View.GONE
            applySort()
        }
        postAdapter.setFooterVisible(false)
        if (isRefresh) {
            binding.swipeRefresh.isRefreshing = false
        }
    }

    override fun onResume() {
        super.onResume()
        if (!showBookmarksOnly) {
            viewModel.refreshBlockedFilter()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SHOW_BOOKMARKS = "show_bookmarks"

        fun newInstance(showBookmarksOnly: Boolean): HomeFragment {
            return HomeFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_SHOW_BOOKMARKS, showBookmarksOnly)
                }
            }
        }
    }
}
