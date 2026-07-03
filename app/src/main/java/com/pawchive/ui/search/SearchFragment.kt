package com.pawchive.ui.search

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.pawchive.R
import com.pawchive.data.api.ApiClient
import com.pawchive.data.model.Creator
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.FragmentSearchBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.CreatorAdapter
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.creator.CreatorProfileFragment
import com.pawchive.ui.post.PostDetailFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val KEY_SEARCHING_POSTS = "searching_posts"
    }

    private val api = ApiClient.publicApi
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var authRepository: AuthRepository

    private lateinit var postAdapter: PostAdapter
    private lateinit var creatorAdapter: CreatorAdapter

    private var allCreators = emptyList<Creator>()
    private var isSearchingPosts = true
    private var searchResults = emptyList<com.pawchive.data.model.Post>()
    private var filteredCreators = emptyList<Creator>()

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        RELEVANCE(R.string.sort_relevance),
        NEWEST_ADDED(R.string.sort_newest_added),
        OLDEST_ADDED(R.string.sort_oldest_added),
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private enum class CreatorSortOption(@param:StringRes val displayNameRes: Int) {
        RELEVANCE(R.string.sort_relevance),
        NAME_ASC(R.string.sort_name_asc),
        NAME_DESC(R.string.sort_name_desc)
    }

    private var currentPostSort = PostSortOption.RELEVANCE
    private var currentCreatorSort = CreatorSortOption.RELEVANCE

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookmarkManager = BookmarkManager(requireContext())
        authRepository = AuthRepository(requireContext())

        savedInstanceState?.let {
            isSearchingPosts = it.getBoolean(KEY_SEARCHING_POSTS, true)
        }

        setupAdapters()
        setupSearchView()
        setupTabLayout()
        setupSortButton()
        setupSwipeRefresh()

        if (savedInstanceState != null) {
            val tabIndex = if (isSearchingPosts) 0 else 1
            binding.tabLayout.getTabAt(tabIndex)?.let { tab ->
                binding.tabLayout.setScrollPosition(tab.position, 0f, true)
            }
        }
        
        // Fetch all creators in the background for local filtering
        fetchCreatorsCache()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SEARCHING_POSTS, isSearchingPosts)
    }

    private fun setupAdapters() {
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
            onBookmarkChanged = { _, _ -> }
        )

        creatorAdapter = CreatorAdapter(
            creators = emptyList(),
            onCreatorClicked = { creator ->
                val creatorFragment = CreatorProfileFragment.newInstance(creator.service, creator.id)
                (activity as? MainActivity)?.loadFragment(creatorFragment)
            }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = postAdapter // Default is posts
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrEmpty()) {
                    performSearch(query, isRefresh = false)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty()) {
                    showSearchPrompt()
                } else if (!isSearchingPosts) {
                    // For creators, perform fast instant local filtering
                    filterCreatorsLocal(newText)
                }
                return true
            }
        })
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isSearchingPosts = tab?.position == 0
                binding.rvResults.adapter = if (isSearchingPosts) postAdapter else creatorAdapter
                updateSortButtonText()
                
                val query = binding.searchView.query.toString()
                if (query.isNotEmpty()) {
                    performSearch(query, isRefresh = false)
                } else {
                    showSearchPrompt()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupSortButton() {
        updateSortButtonText()
        binding.btnSort.setOnClickListener {
            showSortDialog()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            val query = binding.searchView.query.toString()
            if (query.isNotEmpty()) {
                performSearch(query, isRefresh = true)
            } else {
                binding.swipeRefresh.isRefreshing = false
            }
        }
        binding.swipeRefresh.setColorSchemeColors(
            getThemeColor(com.google.android.material.R.attr.colorPrimary)
        )
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun updateSortButtonText() {
        binding.btnSort.text = if (isSearchingPosts) {
            getString(currentPostSort.displayNameRes)
        } else {
            getString(currentCreatorSort.displayNameRes)
        }
    }

    private fun showSortDialog() {
        if (isSearchingPosts) {
            val options = PostSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
            val currentIndex = PostSortOption.values().indexOf(currentPostSort)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_sort)
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    currentPostSort = PostSortOption.values()[which]
                    updateSortButtonText()
                    applyPostSort()
                    dialog.dismiss()
                }
                .show()
        } else {
            val options = CreatorSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
            val currentIndex = CreatorSortOption.values().indexOf(currentCreatorSort)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_sort)
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    currentCreatorSort = CreatorSortOption.values()[which]
                    updateSortButtonText()
                    applyCreatorSort()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun applyPostSort() {
        if (searchResults.isEmpty()) return
        val sorted = when (currentPostSort) {
            PostSortOption.RELEVANCE -> searchResults
            PostSortOption.NEWEST_ADDED -> searchResults.sortedByDescending { it.added }
            PostSortOption.OLDEST_ADDED -> searchResults.sortedBy { it.added }
            PostSortOption.NEWEST_PUBLISHED -> searchResults.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> searchResults.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> searchResults.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> searchResults.sortedBy { it.edited ?: it.published }
        }
        postAdapter.updatePosts(sorted)
    }

    private fun applyCreatorSort() {
        if (filteredCreators.isEmpty()) return
        val sorted = when (currentCreatorSort) {
            CreatorSortOption.RELEVANCE -> filteredCreators
            CreatorSortOption.NAME_ASC -> filteredCreators.sortedBy { it.name.lowercase() }
            CreatorSortOption.NAME_DESC -> filteredCreators.sortedByDescending { it.name.lowercase() }
        }
        creatorAdapter.updateCreators(sorted)
    }

    private fun showSearchPrompt() {
        binding.tvNoResults.visibility = View.VISIBLE
        binding.tvEmptyText.text = getString(R.string.search_initial_hint)
        if (isSearchingPosts) {
            postAdapter.updatePosts(emptyList())
        } else {
            creatorAdapter.updateCreators(emptyList())
        }
    }

    private fun performSearch(query: String, isRefresh: Boolean = false) {
        if (isSearchingPosts) {
            searchPosts(query, isRefresh)
        } else {
            filterCreatorsLocal(query)
        }
    }

    private fun searchPosts(query: String, isRefresh: Boolean = false) {
        if (!isRefresh) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvNoResults.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val results = api.getRecentPosts(query = query)
                searchResults = results
                if (results.isEmpty()) {
                    binding.tvEmptyText.text = getString(R.string.no_posts_found)
                    binding.tvNoResults.visibility = View.VISIBLE
                }
                applyPostSort()
                launch {
                    CreatorNameCache.prefetchCreatorNames(results)
                    postAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "${getString(R.string.fetch_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun fetchCreatorsCache() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                allCreators = api.getCreators()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun filterCreatorsLocal(query: String) {
        val filtered = allCreators.filter { 
            it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true)
        }
        filteredCreators = filtered
        if (filtered.isEmpty()) {
            binding.tvEmptyText.text = getString(R.string.no_posts_found)
            binding.tvNoResults.visibility = View.VISIBLE
        } else {
            binding.tvNoResults.visibility = View.GONE
        }
        applyCreatorSort()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
