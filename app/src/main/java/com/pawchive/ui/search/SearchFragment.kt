package com.pawchive.ui.search

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.data.repository.SearchHistoryManager
import com.pawchive.databinding.FragmentSearchBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.CreatorAdapter
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.adapter.SearchHistoryAdapter
import com.pawchive.ui.creator.CreatorProfileFragment
import com.pawchive.ui.post.PostDetailFragment
import com.pawchive.utils.ErrorMessageHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import kotlinx.coroutines.Job
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
    private lateinit var blockedCreatorManager: BlockedCreatorManager

    private lateinit var postAdapter: PostAdapter
    private lateinit var creatorAdapter: CreatorAdapter
    private lateinit var searchHistoryAdapter: SearchHistoryAdapter
    private lateinit var searchHistoryManager: SearchHistoryManager

    private var allCreators = emptyList<Creator>()
    private var isSearchingPosts = true
    private var searchResults = emptyList<com.pawchive.data.model.Post>()
    private var filteredCreators = emptyList<Creator>()
    private var creatorsCacheLoaded = false
    private var pendingCreatorQuery: String? = null

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        RELEVANCE(R.string.sort_relevance),
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private enum class CreatorSortOption(@param:StringRes val displayNameRes: Int) {
        NAME_ASC(R.string.sort_name_asc),
        NAME_DESC(R.string.sort_name_desc)
    }

    private var currentPostSort = PostSortOption.RELEVANCE
    private var currentCreatorSort = CreatorSortOption.NAME_ASC

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bookmarkManager = BookmarkManager.getInstance(requireContext())
        authRepository = AuthRepository(requireContext())
        blockedCreatorManager = BlockedCreatorManager.getInstance(requireContext())

        savedInstanceState?.let {
            isSearchingPosts = it.getBoolean(KEY_SEARCHING_POSTS, true)
        }

        setupAdapters()
        setupSearchView()
        setupTabLayout()
        setupSortButton()
        setupSwipeRefresh()
        setupSearchHistory()

        if (savedInstanceState != null) {
            val tabIndex = if (isSearchingPosts) 0 else 1
            binding.tabLayout.getTabAt(tabIndex)?.let { tab ->
                binding.tabLayout.setScrollPosition(tab.position, 0f, true)
            }
        }

        fetchCreatorsCache()
        showSearchPrompt()
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
        binding.rvResults.adapter = postAdapter
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrEmpty()) {
                    hideKeyboard()
                    performSearch(query, isRefresh = false)
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrEmpty()) {
                    showSearchPrompt()
                } else {
                    hideHistoryView()
                    if (!isSearchingPosts) {
                        filterCreatorsLocal(newText)
                    }
                }
                return true
            }
        })
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                hideKeyboard()
                isSearchingPosts = tab?.position == 0
                binding.rvResults.adapter = if (isSearchingPosts) postAdapter else creatorAdapter

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
        binding.btnSort.setOnClickListener {
            hideKeyboard()
            showSortSheet()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            hideKeyboard()
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

    private fun showSortSheet() {
        val density = resources.displayMetrics.density
        val sheet = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.sheet_sort_options, null)
        sheet.setContentView(sheetView)

        val radioGroup = sheetView.findViewById<RadioGroup>(R.id.sort_radio_group)

        val entries: List<Pair<Int, String>> = if (isSearchingPosts) {
            PostSortOption.values().map { it.ordinal to getString(it.displayNameRes) }
        } else {
            CreatorSortOption.values().map { it.ordinal to getString(it.displayNameRes) }
        }
        val currentIndex = if (isSearchingPosts) {
            PostSortOption.values().indexOf(currentPostSort)
        } else {
            CreatorSortOption.values().indexOf(currentCreatorSort)
        }

        val buttons = entries.map { (index, label) ->
            RadioButton(requireContext()).apply {
                text = label
                id = View.generateViewId()
                tag = index
                setMinHeight((48 * density).toInt())
                textSize = 15f
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
            }
        }
        buttons.forEach { radioGroup.addView(it) }
        // 先 check 当前项：此时监听器尚未设置，不会触发回调
        radioGroup.check(buttons[currentIndex].id)
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val idx = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? Int
                ?: return@setOnCheckedChangeListener
            if (isSearchingPosts) {
                currentPostSort = PostSortOption.values()[idx]
                applyPostSort()
            } else {
                currentCreatorSort = CreatorSortOption.values()[idx]
                applyCreatorSort()
            }
            sheet.dismiss()
        }
        sheet.show()
    }

    private fun applyPostSort() {
        if (searchResults.isEmpty()) return
        val sorted = when (currentPostSort) {
            PostSortOption.RELEVANCE -> searchResults
            PostSortOption.NEWEST_PUBLISHED -> searchResults.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> searchResults.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> searchResults.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> searchResults.sortedBy { it.edited ?: it.published }
        }
        val filtered = sorted.filter { !blockedCreatorManager.isCreatorBlocked(it.service, it.user) }
        postAdapter.updatePosts(filtered)
    }

    private fun applyCreatorSort() {
        if (filteredCreators.isEmpty()) return
        val sorted = when (currentCreatorSort) {
            CreatorSortOption.NAME_ASC -> filteredCreators.sortedBy { it.name.lowercase() }
            CreatorSortOption.NAME_DESC -> filteredCreators.sortedByDescending { it.name.lowercase() }
        }
        creatorAdapter.updateCreators(sorted)
    }

    private fun showSearchPrompt() {
        val history = searchHistoryManager.getHistory()
        if (history.isNotEmpty()) {
            showHistoryView()
        } else {
            showResultsView()
            binding.tvNoResults.visibility = View.VISIBLE
            binding.progressBar.visibility = View.GONE
            binding.tvEmptyText.text = getString(R.string.search_initial_hint)
            if (isSearchingPosts) {
                postAdapter.updatePosts(emptyList())
            } else {
                creatorAdapter.updateCreators(emptyList())
            }
        }
    }

    private fun performSearch(query: String, isRefresh: Boolean = false) {
        showResultsView()
        searchHistoryManager.addHistory(query)
        if (isSearchingPosts) {
            searchPosts(query, isRefresh)
        } else {
            filterCreatorsLocal(query)
        }
    }

    private var searchJob: Job? = null

    private fun searchPosts(query: String, isRefresh: Boolean = false) {
        if (!isRefresh) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvNoResults.visibility = View.GONE
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                binding.tvNoResults.visibility = View.VISIBLE
                binding.tvEmptyText.text = getString(R.string.search_initial_hint)
                Toast.makeText(context, ErrorMessageHelper.getFriendlyMessage(context, e), Toast.LENGTH_SHORT).show()
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
                creatorsCacheLoaded = true
                pendingCreatorQuery?.let { query ->
                    filterCreatorsLocal(query)
                    pendingCreatorQuery = null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                creatorsCacheLoaded = true
                pendingCreatorQuery?.let { query ->
                    filterCreatorsLocal(query)
                    pendingCreatorQuery = null
                }
            }
        }
    }

    private fun filterCreatorsLocal(query: String) {
        hideHistoryView()
        binding.tvNoResults.visibility = View.GONE

        if (!creatorsCacheLoaded) {
            pendingCreatorQuery = query
            binding.progressBar.visibility = View.VISIBLE
            return
        }

        val filtered = allCreators.filter {
            (it.name.contains(query, ignoreCase = true) || it.id.contains(query, ignoreCase = true))
                    && !blockedCreatorManager.isCreatorBlocked(it.service, it.id)
        }
        filteredCreators = filtered
        binding.progressBar.visibility = View.GONE

        if (filtered.isEmpty()) {
            binding.tvEmptyText.text = getString(R.string.no_creators_found)
            binding.tvNoResults.visibility = View.VISIBLE
        } else {
            binding.tvNoResults.visibility = View.GONE
        }
        applyCreatorSort()
    }

    private fun setupSearchHistory() {
        searchHistoryManager = SearchHistoryManager(requireContext())
        searchHistoryAdapter = SearchHistoryAdapter(
            items = searchHistoryManager.getHistory(),
            onItemClicked = { query ->
                hideKeyboard()
                binding.searchView.setQuery(query, true)
            },
            onDeleteClicked = { query ->
                searchHistoryManager.removeHistory(query)
                refreshHistoryList()
            }
        )
        binding.rvSearchHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSearchHistory.adapter = searchHistoryAdapter

        binding.btnClearAll.setOnClickListener {
            searchHistoryManager.clearAll()
            refreshHistoryList()
            hideHistoryView()
        }

        binding.btnDone.setOnClickListener {
            hideKeyboard()
            hideHistoryView()
        }
    }

    private fun showHistoryView() {
        binding.layoutResults.visibility = View.GONE
        binding.layoutHistory.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
        refreshHistoryList()
    }

    private fun showResultsView() {
        binding.layoutHistory.visibility = View.GONE
        binding.layoutResults.visibility = View.VISIBLE
    }

    private fun hideHistoryView() {
        showResultsView()
    }

    private fun refreshHistoryList() {
        val history = searchHistoryManager.getHistory()
        searchHistoryAdapter.updateItems(history)
        if (history.isEmpty()) {
            hideHistoryView()
        }
    }

    override fun onResume() {
        super.onResume()
        if (searchResults.isNotEmpty() && isSearchingPosts) {
            applyPostSort()
        }
        if (filteredCreators.isNotEmpty() && !isSearchingPosts) {
            val query = binding.searchView.query.toString()
            if (query.isNotEmpty()) {
                filterCreatorsLocal(query)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        binding.searchView.clearFocus()
        imm.hideSoftInputFromWindow(binding.searchView.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
