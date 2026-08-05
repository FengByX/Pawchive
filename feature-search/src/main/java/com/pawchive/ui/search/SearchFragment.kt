package com.pawchive.ui.search

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pawchive.common.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BlockedCreatorManager
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.data.repository.SearchHistoryManager
import com.pawchive.common.databinding.FragmentSearchBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.ui.adapter.CreatorAdapter
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.adapter.SearchHistoryAdapter
import com.pawchive.utils.ErrorStateViewHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.tabs.TabLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SearchFragment : Fragment() {

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SearchViewModel by viewModels()

    companion object {
        private const val KEY_SEARCHING_POSTS = "searching_posts"
    }

    @Inject
    lateinit var bookmarkManager: BookmarkManager
    @Inject lateinit var authRepository: AuthRepository
    @Inject
    lateinit var blockedCreatorManager: BlockedCreatorManager

    private lateinit var postAdapter: PostAdapter
    private lateinit var creatorAdapter: CreatorAdapter
    private lateinit var searchHistoryAdapter: SearchHistoryAdapter
    @Inject
    lateinit var searchHistoryManager: SearchHistoryManager

    private var isSearchingPosts = true
    private var currentPostSort = PostSortOption.RELEVANCE
    private var currentCreatorSort = CreatorSortOption.NAME_ASC

    // 最近一次搜索关键词，用于错误页"重试"
    private var lastQuery: String = ""
    private lateinit var errorStateView: ErrorStateViewHelper.Bound

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            isSearchingPosts = it.getBoolean(KEY_SEARCHING_POSTS, true)
        }

        setupAdapters()
        setupSearchView()
        setupTabLayout()
        setupSortButton()
        setupSwipeRefresh()
        setupSearchHistory()
        setupFilterChips()
        // 绑定内嵌错误页（FEATURE-006）
        errorStateView = ErrorStateViewHelper.bind(binding.root) {
            if (lastQuery.isNotEmpty()) {
                performSearch(lastQuery, isRefresh = false)
            }
        }
        observeUiState()

        if (savedInstanceState != null) {
            val tabIndex = if (isSearchingPosts) 0 else 1
            binding.tabLayout.getTabAt(tabIndex)?.let { tab ->
                binding.tabLayout.setScrollPosition(tab.position, 0f, true)
            }
        }

        viewModel.loadCreatorsCache()
        showSearchPrompt()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SEARCHING_POSTS, isSearchingPosts)
    }

    /**
     * 订阅 ViewModel 状态，按当前 Tab 渲染 UI（P2 FRONTEND-008）。
     * - 帖子/创作者结果变化时更新对应 Adapter
     * - loading 状态控制 ProgressBar 与 SwipeRefresh
     * - 错误信息以 Toast 展示并清除
     * - 空结果提示控制 tvNoResults 显示
     */
    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 更新帖子结果
                    postAdapter.updatePosts(state.postResults)
                    // 创作者名称预取完成后仅刷新数据区条目（ARCH-012：避免全量 notifyDataSetChanged）
                    if (state.postResults.isNotEmpty()) {
                        launch {
                            CreatorNameCache.prefetchCreatorNames(state.postResults)
                            postAdapter.refreshCreatorNames()
                        }
                    }
                    // 更新创作者结果
                    creatorAdapter.updateCreators(state.creatorResults)

                    // loading 状态
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.swipeRefresh.isRefreshing = state.isLoading

                    // 空结果提示
                    if (state.emptyHintResId != null) {
                        binding.tvEmptyText.text = getString(state.emptyHintResId)
                        binding.tvNoResults.visibility = View.VISIBLE
                    } else if (state.postResults.isNotEmpty() || state.creatorResults.isNotEmpty()) {
                        binding.tvNoResults.visibility = View.GONE
                    }

                    // 错误提示（FEATURE-006）：列表为空时展示内嵌错误页，有结果时仅 Toast 提示
                    state.errorMessage?.let { msg ->
                        val hasResults = state.postResults.isNotEmpty() || state.creatorResults.isNotEmpty()
                        if (!hasResults) {
                            // 列表为空：展示内嵌错误页，提供重试入口
                            binding.tvNoResults.visibility = View.GONE
                            errorStateView.show(msg)
                        } else {
                            // 已有结果：错误以 Toast 呈现，不打断浏览
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        viewModel.clearError()
                    } ?: run {
                        // 无错误时隐藏错误页
                        errorStateView.hide()
                    }
                }
            }
        }

        // 订阅屏蔽列表变化，触发重新过滤
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                blockedCreatorManager.blockedCreatorsFlow.collect {
                    viewModel.refreshBlockedFilter()
                }
            }
        }
    }

    private fun setupAdapters() {
        postAdapter = PostAdapter(
            posts = emptyList(),
            bookmarkManager = bookmarkManager,
            authRepository = authRepository,
            lifecycleScope = viewLifecycleOwner.lifecycleScope,
            onPostClicked = { post ->
                (activity as? AppNavigator)?.openPostDetail(post.service, post.user, post.id)
            },
            onCreatorClicked = { service, creatorId ->
                (activity as? AppNavigator)?.openCreatorProfile(service, creatorId)
            },
            onBookmarkChanged = { _, _ -> }
        )

        creatorAdapter = CreatorAdapter(
            onCreatorClicked = { creator ->
                (activity as? AppNavigator)?.openCreatorProfile(creator.service, creator.id)
            }
        )

        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = postAdapter
    }

    private fun setupSearchView() {
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
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
                        viewModel.filterCreatorsLocal(newText)
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

    /**
     * 搜索筛选 chips（FEATURE-004；ARCH-FEATURE-001 离线搜索入口）。
     * - 来源筛选：弹出对话框选择平台
     * - 仅含附件：切换开关
     * - 仅看收藏：切换开关
     * - 离线搜索：切换本地全文搜索模式（chip 变化后按当前查询重新搜索）
     */
    private fun setupFilterChips() {
        binding.chipService.setOnClickListener {
            showServiceFilterDialog()
        }
        binding.chipAttachment.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setHasAttachmentOnly(isChecked)
        }
        binding.chipBookmarked.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setBookmarkedOnly(isChecked)
        }
        binding.chipOffline.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setOfflineMode(isChecked)
            val query = binding.searchView.query.toString()
            if (query.isNotEmpty()) {
                performSearch(query, isRefresh = false)
            }
        }
    }

    private fun showServiceFilterDialog() {
        val services = arrayOf("fanbox", "fantia", "patreon", "discord")
        val current = viewModel.uiState.value.selectedService
        val checkedItem = services.indexOf(current).let { if (it >= 0) it else -1 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_service)
            .setSingleChoiceItems(services, checkedItem) { dialog, which ->
                viewModel.setServiceFilter(services[which])
                binding.chipService.text = services[which]
                binding.chipService.isChecked = true
                dialog.dismiss()
            }
            .setNeutralButton(R.string.filter_reset) { _, _ ->
                viewModel.setServiceFilter(null)
                binding.chipService.text = getString(R.string.filter_service)
                binding.chipService.isChecked = false
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                viewModel.setPostSort(currentPostSort)
            } else {
                currentCreatorSort = CreatorSortOption.values()[idx]
                viewModel.setCreatorSort(currentCreatorSort)
            }
            sheet.dismiss()
        }
        sheet.show()
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
            // 清空关键词时隐藏错误页
            errorStateView.hide()
            if (isSearchingPosts) {
                postAdapter.updatePosts(emptyList())
            } else {
                creatorAdapter.updateCreators(emptyList())
            }
        }
    }

    private fun performSearch(query: String, isRefresh: Boolean = false) {
        showResultsView()
        lastQuery = query
        // 发起新搜索前隐藏错误页
        errorStateView.hide()
        searchHistoryManager.addHistory(query)
        if (isSearchingPosts) {
            if (viewModel.uiState.value.offlineMode) {
                // ARCH-FEATURE-001：离线全文搜索（收藏内容本地索引，无需网络）
                viewModel.searchOffline(query)
            } else {
                viewModel.searchPosts(query)
            }
        } else {
            viewModel.filterCreatorsLocal(query)
        }
    }

    private fun setupSearchHistory() {
        searchHistoryAdapter = SearchHistoryAdapter(
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
        // 屏蔽状态可能变化，触发 ViewModel 重新过滤已加载数据
        viewModel.refreshBlockedFilter()
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
