package com.pawchive.ui.favorites

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pawchive.common.R
import com.pawchive.data.repository.AuthRepository
import com.pawchive.common.databinding.FragmentAccountFavoritesBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.ui.adapter.FavoriteCreatorAdapter
import com.pawchive.ui.adapter.FavoritePostAdapter
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 账号收藏 Fragment（ARCH-006 / FRONTEND-008）。
 *
 * 职责：渲染 [AccountFavoritesViewModel.uiState]、转发用户事件（Tab 切换/排序/刷新/移除收藏/导航）。
 * 云端同步、分页、排序、移除收藏全部由 ViewModel 承担。
 */
@AndroidEntryPoint
class AccountFavoritesFragment : Fragment() {
    private var _binding: FragmentAccountFavoritesBinding? = null
    private val binding get() = _binding!!
    @Inject lateinit var authRepository: AuthRepository
    private val viewModel: AccountFavoritesViewModel by viewModels()

    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
    }

    private lateinit var postAdapter: FavoritePostAdapter
    private lateinit var creatorAdapter: FavoriteCreatorAdapter

    private var currentTab = 1 // 0 = posts, 1 = creators（默认展示"收藏作者"，FEATURE）
    // 当前排序（用于按钮文字与对话框索引，ViewModel 持有实际排序状态）
    private var currentPostSort = FavoritePostSortOption.NEWEST_EDITED
    private var currentCreatorSort = FavoriteCreatorSortOption.NEWEST_UPDATED

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAccountFavoritesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState?.let {
            currentTab = it.getInt(KEY_CURRENT_TAB, 1)
        }

        setupAdapters()
        setupTabLayout()
        setupSortButton()
        setupSwipeRefresh()

        // 检查登录状态
        if (!authRepository.isLoggedIn()) {
            binding.tvNotLoggedIn.visibility = View.VISIBLE
            binding.tabLayout.visibility = View.GONE
            binding.rvFavorites.visibility = View.GONE
            return
        }
        binding.tvNotLoggedIn.visibility = View.GONE
        binding.tabLayout.visibility = View.VISIBLE

        observeUiState()

        // 同步 Tab 选中状态：无论首次还是恢复，都先设置视觉位置
        binding.tabLayout.getTabAt(currentTab)?.let { tab ->
            if (savedInstanceState == null) {
                // 首次进入：如果该 Tab 已经默认选中，select() 不会回调 onTabSelected，
                // 所以下面必须手动调用 viewModel.setTab() 确保数据加载被触发
                tab.select()
            } else {
                binding.tabLayout.setScrollPosition(tab.position, 0f, true)
            }
        }
        // 关键修复：不依赖 TabLayout 回调，始终手动触发 setTab，保证首次进入必加载数据
        viewModel.setTab(currentTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    postAdapter.updatePosts(state.posts)
                    postAdapter.setFooterVisible(state.hasMorePosts)
                    creatorAdapter.updateCreators(state.creators)

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

                    // 单次成功反馈（如"已取消收藏"）
                    state.toastMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        viewModel.consumeToast()
                    }

                    // 失败反馈：统一 Toast（收藏页无内嵌错误页）
                    state.errorMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearError()
                    }

                    // 空状态：由 ViewModel 在请求成功后明确设置
                    if (state.emptyVisible) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvFavorites.visibility = View.GONE
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.rvFavorites.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupAdapters() {
        postAdapter = FavoritePostAdapter(
            onPostClicked = { post ->
                (activity as? AppNavigator)?.openPostDetail(post.service, post.user, post.id)
            },
            onCreatorClicked = { service, creatorId ->
                (activity as? AppNavigator)?.openCreatorProfile(service, creatorId)
            },
            onRemoveFavorite = { post ->
                viewModel.removePost(post)
            },
            onLoadMore = { viewModel.loadMorePosts() }
        )

        creatorAdapter = FavoriteCreatorAdapter(
            onCreatorClicked = { service, creatorId ->
                (activity as? AppNavigator)?.openCreatorProfile(service, creatorId)
            }
        )

        binding.rvFavorites.adapter = postAdapter
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                handleTabChanged(tab?.position ?: 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            // 修复：Tab 已选中时再次点击也要刷新数据（防空白 + 便捷刷新入口）
            override fun onTabReselected(tab: TabLayout.Tab?) {
                handleTabChanged(tab?.position ?: 0)
            }
        })
    }

    /**
     * 统一处理 Tab 切换/重选：更新本地状态、切换适配器、通知 ViewModel 加载。
     * 重选时 viewModel.refresh() 内部会根据 loading 状态去重，不会重复发起请求。
     */
    private fun handleTabChanged(tab: Int) {
        val isTabChanged = currentTab != tab
        currentTab = tab
        updateSortButtonText()
        binding.rvFavorites.adapter = if (currentTab == 0) postAdapter else creatorAdapter
        if (isTabChanged) {
            viewModel.setTab(currentTab)
        } else {
            // 同一 Tab 被重新点击：直接刷新当前 Tab 数据
            viewModel.refresh()
        }
    }

    private fun setupSortButton() {
        updateSortButtonText()
        binding.btnSort.setOnClickListener {
            showSortDialog()
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
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
        binding.btnSort.text = if (currentTab == 0) {
            getString(currentPostSort.displayNameRes)
        } else {
            getString(currentCreatorSort.displayNameRes)
        }
    }

    private fun showSortDialog() {
        if (currentTab == 0) {
            val options = FavoritePostSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
            val currentIndex = FavoritePostSortOption.values().indexOf(currentPostSort)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_sort)
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    currentPostSort = FavoritePostSortOption.values()[which]
                    updateSortButtonText()
                    viewModel.setPostSort(currentPostSort)
                    dialog.dismiss()
                }
                .show()
        } else {
            val options = FavoriteCreatorSortOption.values().map { getString(it.displayNameRes) }.toTypedArray()
            val currentIndex = FavoriteCreatorSortOption.values().indexOf(currentCreatorSort)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.select_sort)
                .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                    currentCreatorSort = FavoriteCreatorSortOption.values()[which]
                    updateSortButtonText()
                    viewModel.setCreatorSort(currentCreatorSort)
                    dialog.dismiss()
                }
                .show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
