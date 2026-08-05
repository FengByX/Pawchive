package com.pawchive.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pawchive.common.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.common.databinding.FragmentHomeBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.utils.ErrorStateViewHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 首页 Fragment（ARCH-006 / FRONTEND-008）。
 *
 * 职责：渲染 [HomeViewModel.uiState]、转发用户事件（排序/刷新/加载更多/导航/收藏变更）。
 * 网络请求、分页、屏蔽过滤、排序、收藏模式加载全部由 ViewModel 承担。
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val viewModel: HomeViewModel by viewModels()

    private lateinit var postAdapter: PostAdapter
    @Inject
    lateinit var bookmarkManager: BookmarkManager
    @Inject lateinit var authRepository: AuthRepository

    private var showBookmarksOnly = false
    // 当前排序（用于按钮文字与对话框索引，ViewModel 持有实际排序状态）
    private var currentSort = HomePostSortOption.NEWEST_PUBLISHED

    // 内嵌错误页（FEATURE-006）
    private lateinit var errorStateView: ErrorStateViewHelper.Bound

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

        setupRecyclerView()
        setupSwipeRefresh()
        setupSortButton()
        // 绑定内嵌错误页（FEATURE-006）
        errorStateView = ErrorStateViewHelper.bind(binding.root) {
            viewModel.refresh()
        }

        observeUiState()
        viewModel.init(showBookmarksOnly)
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
            // 列表与 footer
            postAdapter.updatePosts(state.posts)
            postAdapter.setFooterVisible(state.hasMore)

            // 加载指示：初始加载显示 ProgressBar；下拉刷新时由 swipeRefresh 指示
            binding.progressBar.visibility =
                if (state.isLoading && !binding.swipeRefresh.isRefreshing) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            if (!state.isLoading) {
                binding.swipeRefresh.isRefreshing = false
            }

            // 错误状态（FEATURE-006 智能降级：空列表展示错误页，有内容仅 Toast）
            state.errorMessage?.let { msg ->
                if (state.posts.isEmpty()) {
                    binding.tvEmpty.visibility = View.GONE
                    errorStateView.show(msg)
                } else {
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                viewModel.clearError()
            } ?: run {
                errorStateView.hide()
            }

            // 空状态提示
            if (state.posts.isEmpty() && state.errorMessage == null && !state.isLoading) {
                binding.tvEmpty.text = getString(state.emptyHintResId)
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                binding.tvEmpty.visibility = View.GONE
            }

            // 创作者名字预取（UI 渲染优化，限流 300ms）
            if (state.posts.isNotEmpty()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    var lastRefreshAt = 0L
                    CreatorNameCache.prefetchCreatorNames(state.posts) {
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
            onCreatorClicked = { service, creatorId ->
                (activity as? AppNavigator)?.openCreatorProfile(service, creatorId)
            },
            onBookmarkChanged = { _, _ ->
                viewModel.onBookmarkChanged()
            },
            onLoadMore = { viewModel.loadMore() }
        )

        binding.rvPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPosts.adapter = postAdapter
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            if (showBookmarksOnly) {
                // 收藏模式无网络请求，直接重读本地并关闭刷新指示
                viewModel.loadBookmarks()
                binding.swipeRefresh.isRefreshing = false
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
        val options = HomePostSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
        val currentIndex = HomePostSortOption.values().indexOf(currentSort)

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_sort)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                currentSort = HomePostSortOption.values()[which]
                binding.btnSort.text = getString(currentSort.displayNameRes)
                viewModel.setSort(currentSort)
                dialog.dismiss()
            }
            .show()
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
