package com.pawchive.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.pawchive.R
import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Post
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.FragmentHomeBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.creator.CreatorProfileFragment
import com.pawchive.ui.post.PostDetailFragment
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var postAdapter: PostAdapter
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var authRepository: AuthRepository
    private val api = ApiClient.publicApi

    private var showBookmarksOnly = false
    private val loadedPosts = mutableListOf<Post>()
    private var currentOffset = 0
    private val pageSize = 50

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        NEWEST_ADDED(R.string.sort_newest_added),
        OLDEST_ADDED(R.string.sort_oldest_added),
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private var currentSort = PostSortOption.NEWEST_EDITED

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
        bookmarkManager = BookmarkManager(requireContext())
        authRepository = AuthRepository(requireContext())

        setupRecyclerView()
        setupSwipeRefresh()
        setupSortButton()

        if (showBookmarksOnly) {
            loadBookmarks(isRefresh = false)
        } else {
            loadRecentPosts(isRefresh = false)
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
            currentOffset = 0
            loadedPosts.clear()
            if (showBookmarksOnly) {
                loadBookmarks(isRefresh = true)
            } else {
                loadRecentPosts(isRefresh = true)
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
            PostSortOption.NEWEST_ADDED -> postsToSort.sortedByDescending { it.added }
            PostSortOption.OLDEST_ADDED -> postsToSort.sortedBy { it.added }
            PostSortOption.NEWEST_PUBLISHED -> postsToSort.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> postsToSort.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> postsToSort.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> postsToSort.sortedBy { it.edited ?: it.published }
        }

        postAdapter.updatePosts(sorted)
    }

    private fun loadRecentPosts(isRefresh: Boolean = false) {
        if (!isRefresh) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvEmpty.visibility = View.GONE

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val posts = api.getRecentPosts(offset = currentOffset)
                loadedPosts.clear()
                loadedPosts.addAll(posts)
                if (posts.isEmpty()) {
                    binding.tvEmpty.visibility = View.VISIBLE
                } else {
                    applySort()
                    updateLoadMoreButton(posts.size)
                    launch {
                        CreatorNameCache.prefetchCreatorNames(posts)
                        postAdapter.notifyDataSetChanged()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "${getString(R.string.fetch_error)}: ${e.message}", Toast.LENGTH_LONG).show()
                binding.tvEmpty.text = getString(R.string.connection_error)
                binding.tvEmpty.visibility = View.VISIBLE
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun loadMorePosts() {
        postAdapter.setFooterVisible(false)
        binding.progressBar.visibility = View.VISIBLE
        currentOffset += pageSize

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val morePosts = api.getRecentPosts(offset = currentOffset)
                loadedPosts.addAll(morePosts)
                applySort()
                updateLoadMoreButton(morePosts.size)
                launch {
                    CreatorNameCache.prefetchCreatorNames(morePosts)
                    postAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                currentOffset -= pageSize
                postAdapter.setFooterVisible(true)
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateLoadMoreButton(loadedCount: Int) {
        postAdapter.setFooterVisible(loadedCount >= pageSize)
    }

    private fun loadBookmarks(isRefresh: Boolean = false) {
        val bookmarkedPosts = bookmarkManager.getBookmarkedPosts()
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
