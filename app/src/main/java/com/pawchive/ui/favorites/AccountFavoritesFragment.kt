package com.pawchive.ui.favorites

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.pawchive.R
import com.pawchive.data.model.FavoriteCreator
import com.pawchive.data.model.FavoritePost
import com.pawchive.data.repository.AuthRepository
import com.pawchive.databinding.FragmentAccountFavoritesBinding
import com.pawchive.ui.adapter.FavoriteCreatorAdapter
import com.pawchive.ui.adapter.FavoritePostAdapter
import com.pawchive.ui.creator.CreatorProfileFragment
import com.pawchive.ui.post.PostDetailFragment
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

class AccountFavoritesFragment : Fragment() {
    private var _binding: FragmentAccountFavoritesBinding? = null
    private val binding get() = _binding!!
    private lateinit var authRepository: AuthRepository
    
    companion object {
        private const val KEY_CURRENT_TAB = "current_tab"
    }
    
    private var loadedPosts = mutableListOf<FavoritePost>()
    private var loadedCreators = mutableListOf<FavoriteCreator>()
    private var currentOffset = 0
    private val pageSize = 50
    
    private lateinit var postAdapter: FavoritePostAdapter
    private lateinit var creatorAdapter: FavoriteCreatorAdapter
    
    private var currentTab = 0 // 0 = posts, 1 = creators

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        FAV_NEWEST(R.string.sort_fav_newest),
        FAV_OLDEST(R.string.sort_fav_oldest),
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private enum class CreatorSortOption(@param:StringRes val displayNameRes: Int) {
        FAV_NEWEST(R.string.sort_fav_newest),
        FAV_OLDEST(R.string.sort_fav_oldest),
        NEWEST_UPDATED(R.string.sort_newest_updated),
        OLDEST_UPDATED(R.string.sort_oldest_updated),
        NAME_ASC(R.string.sort_name_asc),
        NAME_DESC(R.string.sort_name_desc)
    }

    private var currentPostSort = PostSortOption.NEWEST_EDITED
    private var currentCreatorSort = CreatorSortOption.NEWEST_UPDATED

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
        authRepository = AuthRepository(requireContext())
        
        savedInstanceState?.let {
            currentTab = it.getInt(KEY_CURRENT_TAB, 0)
        }
        
        setupAdapters()
        setupTabLayout()
        setupSortButton()
        setupSwipeRefresh()

        if (savedInstanceState == null) {
            binding.tabLayout.getTabAt(currentTab)?.select()
        } else {
            binding.tabLayout.getTabAt(currentTab)?.let { tab ->
                binding.tabLayout.setScrollPosition(tab.position, 0f, true)
            }
        }

        // 检查登录状态
        if (!authRepository.isLoggedIn()) {
            binding.tvNotLoggedIn.visibility = View.VISIBLE
            binding.tabLayout.visibility = View.GONE
            binding.rvFavorites.visibility = View.GONE
        } else {
            binding.tvNotLoggedIn.visibility = View.GONE
            binding.tabLayout.visibility = View.VISIBLE
            binding.rvFavorites.visibility = View.VISIBLE
            if (currentTab == 0) {
                loadFavoritePosts()
            } else {
                loadFavoriteCreators()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentTab)
    }

    private fun setupAdapters() {
        postAdapter = FavoritePostAdapter(
            posts = emptyList(),
            onPostClicked = { post ->
                // 导航到帖子详情
                val fragment = PostDetailFragment.newInstance(
                    service = post.service,
                    creatorId = post.user,
                    postId = post.id
                )
                (activity as? com.pawchive.ui.MainActivity)?.loadFragment(fragment)
            },
            onCreatorClicked = { service, creatorId ->
                // 导航到创作者页面
                val fragment = CreatorProfileFragment.newInstance(
                    service = service,
                    creatorId = creatorId
                )
                (activity as? com.pawchive.ui.MainActivity)?.loadFragment(fragment)
            },
            onRemoveFavorite = { post ->
                removePostFromFavorites(post)
            },
            onLoadMore = { loadMorePosts() }
        )
        
        creatorAdapter = FavoriteCreatorAdapter(
            creators = emptyList(),
            onCreatorClicked = { service, creatorId ->
                // 导航到创作者页面
                val fragment = CreatorProfileFragment.newInstance(
                    service = service,
                    creatorId = creatorId
                )
                (activity as? com.pawchive.ui.MainActivity)?.loadFragment(fragment)
            }
        )
        
        binding.rvFavorites.adapter = postAdapter
    }

    private fun setupTabLayout() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                updateSortButtonText()
                if (currentTab == 0) {
                    binding.rvFavorites.adapter = postAdapter
                    if (loadedPosts.isEmpty()) {
                        loadFavoritePosts()
                    } else {
                        applyPostSort()
                        updateLoadMoreButton()
                    }
                } else {
                    binding.rvFavorites.adapter = creatorAdapter
                    if (loadedCreators.isEmpty()) {
                        loadFavoriteCreators()
                    } else {
                        applyCreatorSort()
                    }
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
            currentOffset = 0
            if (currentTab == 0) {
                loadedPosts.clear()
                loadFavoritePosts(isRefresh = true)
            } else {
                loadedCreators.clear()
                loadFavoriteCreators(isRefresh = true)
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
        binding.btnSort.text = if (currentTab == 0) {
            getString(currentPostSort.displayNameRes)
        } else {
            getString(currentCreatorSort.displayNameRes)
        }
    }

    private fun showSortDialog() {
        if (currentTab == 0) {
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
        if (loadedPosts.isEmpty()) return
        val sorted = when (currentPostSort) {
            PostSortOption.FAV_NEWEST -> loadedPosts.sortedByDescending { it.favedSeq ?: 0 }
            PostSortOption.FAV_OLDEST -> loadedPosts.sortedBy { it.favedSeq ?: 0 }
            PostSortOption.NEWEST_PUBLISHED -> loadedPosts.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> loadedPosts.sortedBy { it.published }
            PostSortOption.NEWEST_EDITED -> loadedPosts.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> loadedPosts.sortedBy { it.edited ?: it.published }
        }
        postAdapter.updatePosts(sorted)
    }

    private fun applyCreatorSort() {
        if (loadedCreators.isEmpty()) return
        val sorted = when (currentCreatorSort) {
            CreatorSortOption.FAV_NEWEST -> loadedCreators.sortedByDescending { it.favedSeq ?: 0 }
            CreatorSortOption.FAV_OLDEST -> loadedCreators.sortedBy { it.favedSeq ?: 0 }
            CreatorSortOption.NEWEST_UPDATED -> loadedCreators.sortedByDescending { it.updated ?: it.indexed ?: "" }
            CreatorSortOption.OLDEST_UPDATED -> loadedCreators.sortedBy { it.updated ?: it.indexed ?: "" }
            CreatorSortOption.NAME_ASC -> loadedCreators.sortedBy { it.name.lowercase() }
            CreatorSortOption.NAME_DESC -> loadedCreators.sortedByDescending { it.name.lowercase() }
        }
        creatorAdapter.updateCreators(sorted)
    }

    private fun loadFavoritePosts(isRefresh: Boolean = false) {
        if (!isRefresh) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvEmpty.visibility = View.GONE
        currentOffset = 0
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (_binding == null) return@launch
                val result = authRepository.syncFavoritePosts()
                binding.progressBar.visibility = View.GONE
                
                if (result.isSuccess) {
                    val posts = result.getOrNull() ?: emptyList()
                    loadedPosts.clear()
                    loadedPosts.addAll(posts)
                    applyPostSort()
                    
                    if (posts.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvFavorites.visibility = View.GONE
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.rvFavorites.visibility = View.VISIBLE
                    }
                    updateLoadMoreButton()
                } else {
                    val exception = result.exceptionOrNull()
                    if (exception !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(
                            requireContext(),
                            "${getString(R.string.fetch_error)}: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                binding.swipeRefresh.isRefreshing = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled, ignore
            }
        }
    }

    private fun loadMorePosts() {
        binding.progressBar.visibility = View.VISIBLE
        currentOffset += pageSize
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (_binding == null) return@launch
                val result = authRepository.syncFavoritePosts(currentOffset)
                binding.progressBar.visibility = View.GONE
                
                if (result.isSuccess) {
                    val newPosts = result.getOrNull() ?: emptyList()
                    loadedPosts.addAll(newPosts)
                    applyPostSort()
                    updateLoadMoreButton()
                } else {
                    currentOffset -= pageSize
                    val exception = result.exceptionOrNull()
                    if (exception !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(
                            requireContext(),
                            "${getString(R.string.fetch_error)}: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled, ignore
            }
        }
    }

    private fun loadFavoriteCreators(isRefresh: Boolean = false) {
        if (!isRefresh) {
            binding.progressBar.visibility = View.VISIBLE
        }
        binding.tvEmpty.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (_binding == null) return@launch
                val result = authRepository.syncFavoriteCreators()
                binding.progressBar.visibility = View.GONE
                
                if (result.isSuccess) {
                    val creators = result.getOrNull() ?: emptyList()
                    loadedCreators.clear()
                    loadedCreators.addAll(creators)
                    applyCreatorSort()
                    
                    if (creators.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvFavorites.visibility = View.GONE
                    } else {
                        binding.tvEmpty.visibility = View.GONE
                        binding.rvFavorites.visibility = View.VISIBLE
                    }
                } else {
                    val exception = result.exceptionOrNull()
                    if (exception !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(
                            requireContext(),
                            "${getString(R.string.fetch_error)}: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                binding.swipeRefresh.isRefreshing = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled, ignore
            }
        }
    }

    private fun removePostFromFavorites(post: FavoritePost) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (_binding == null) return@launch
                val result = authRepository.removePostFromFavorites(post.service, post.user, post.id)
                
                if (result.isSuccess) {
                    loadedPosts.remove(post)
                    applyPostSort()
                    Toast.makeText(requireContext(), getString(R.string.bookmark_removed), Toast.LENGTH_SHORT).show()
                    
                    if (loadedPosts.isEmpty()) {
                        binding.tvEmpty.visibility = View.VISIBLE
                        binding.rvFavorites.visibility = View.GONE
                    }
                } else {
                    val exception = result.exceptionOrNull()
                    if (exception !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(
                            requireContext(),
                            "${getString(R.string.bookmark_removed)}: ${exception?.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled, ignore
            }
        }
    }

    private fun updateLoadMoreButton() {
        postAdapter.setFooterVisible(loadedPosts.size >= currentOffset + pageSize)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}