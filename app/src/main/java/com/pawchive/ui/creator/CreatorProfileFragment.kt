package com.pawchive.ui.creator

import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.pawchive.R
import com.pawchive.data.api.ApiClient
import com.pawchive.data.repository.AuthRepository
import com.pawchive.data.repository.BookmarkManager
import com.pawchive.data.repository.CreatorNameCache
import com.pawchive.databinding.FragmentCreatorProfileBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.PostAdapter
import com.pawchive.ui.post.PostDetailFragment
import kotlinx.coroutines.launch

class CreatorProfileFragment : Fragment() {

    private var _binding: FragmentCreatorProfileBinding? = null
    private val binding get() = _binding!!

    private lateinit var postAdapter: PostAdapter
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var authRepository: AuthRepository
    private val api = ApiClient.publicApi

    private var service: String = ""
    private var creatorId: String = ""

    private val loadedPosts = mutableListOf<com.pawchive.data.model.Post>()
    private var currentOffset = 0
    private val pageSize = 50

    private enum class PostSortOption(@param:StringRes val displayNameRes: Int) {
        NEWEST_PUBLISHED(R.string.sort_newest_published),
        OLDEST_PUBLISHED(R.string.sort_oldest_published),
        NEWEST_ADDED(R.string.sort_newest_added),
        OLDEST_ADDED(R.string.sort_oldest_added),
        NEWEST_EDITED(R.string.sort_newest_edited),
        OLDEST_EDITED(R.string.sort_oldest_edited)
    }

    private var currentSort = PostSortOption.NEWEST_PUBLISHED

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
        bookmarkManager = BookmarkManager(requireContext())
        authRepository = AuthRepository(requireContext())

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupRecyclerView()
        setupBookmarkButton()
        setupLoadMoreButton()
        setupSortButton()
        loadCreatorDetails()
        loadCreatorAnnouncements()
        loadCreatorLinks()
        loadCreatorPosts()
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
            onBookmarkChanged = { _, _ -> }
        )
        binding.rvCreatorPosts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCreatorPosts.adapter = postAdapter
    }

    private fun setupLoadMoreButton() {
        binding.btnLoadMore.setOnClickListener {
            loadMorePosts()
        }
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
        if (loadedPosts.isEmpty()) return
        val sorted = when (currentSort) {
            PostSortOption.NEWEST_PUBLISHED -> loadedPosts.sortedByDescending { it.published }
            PostSortOption.OLDEST_PUBLISHED -> loadedPosts.sortedBy { it.published }
            PostSortOption.NEWEST_ADDED -> loadedPosts.sortedByDescending { it.added }
            PostSortOption.OLDEST_ADDED -> loadedPosts.sortedBy { it.added }
            PostSortOption.NEWEST_EDITED -> loadedPosts.sortedByDescending { it.edited ?: it.published }
            PostSortOption.OLDEST_EDITED -> loadedPosts.sortedBy { it.edited ?: it.published }
        }
        postAdapter.updatePosts(sorted)
    }

    private fun setupBookmarkButton() {
        val isBookmarked = bookmarkManager.isCreatorBookmarked(service, creatorId)
        updateBookmarkIcon(isBookmarked)

        binding.btnCreatorBookmark.setOnClickListener {
            val newStatus = !bookmarkManager.isCreatorBookmarked(service, creatorId)
            // 本地立即更新，保持界面响应性
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

            // 登录状态下同步到服务器，让收藏在账号收藏中可见
            if (authRepository.isLoggedIn()) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = if (newStatus) {
                        authRepository.addCreatorToFavorites(service, creatorId)
                    } else {
                        authRepository.removeCreatorFromFavorites(service, creatorId)
                    }
                    if (result.isFailure) {
                        // 服务器同步失败，回滚本地状态
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

    private fun loadCreatorDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val profile = api.getCreatorProfile(service, creatorId)
                binding.tvCreatorTitle.text = profile.name
                binding.tvCreatorService.text = profile.service.uppercase()
                CreatorNameCache.cacheCreatorName(service, creatorId, profile.name)
                loadAvatar()
            } catch (e: Exception) {
                e.printStackTrace()
                binding.tvCreatorTitle.text = creatorId
                binding.tvCreatorService.text = service.uppercase()
                loadAvatar()
            }
        }
    }

    private fun loadAvatar() {
        val avatarUrl = "https://pawchive.pw/icons/$service/$creatorId"
        binding.ivCreatorAvatar.load(avatarUrl) {
            crossfade(true)
            placeholder(android.R.drawable.ic_menu_gallery)
            error(android.R.drawable.ic_menu_report_image)
        }
    }

    private fun loadCreatorAnnouncements() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val announcements = api.getCreatorAnnouncements(service, creatorId)
                if (announcements.isNotEmpty()) {
                    binding.tvAnnouncementsHeader.visibility = View.VISIBLE
                    binding.layoutAnnouncements.removeAllViews()
                    for (announcement in announcements) {
                        val textView = TextView(requireContext()).apply {
                            text = Html.fromHtml(announcement.content ?: "", Html.FROM_HTML_MODE_COMPACT)
                            setTextColor(resources.getColor(R.color.text_secondary, null))
                            textSize = 13f
                            setPadding(0, 8, 0, 8)
                            background = resources.getDrawable(R.drawable.comment_bg, null)
                            setPadding(12, 12, 12, 12)
                        }
                        binding.layoutAnnouncements.addView(textView)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadCreatorLinks() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val links = api.getCreatorLinks(service, creatorId)
                if (links.isNotEmpty()) {
                    binding.tvLinksHeader.visibility = View.VISIBLE
                    binding.layoutLinks.removeAllViews()
                    for (link in links) {
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadCreatorPosts() {
        binding.progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val posts = api.getCreatorPosts(service, creatorId, offset = currentOffset)
                loadedPosts.clear()
                loadedPosts.addAll(posts)
                applySort()
                updateLoadMoreButton(posts.size)
                postAdapter.notifyDataSetChanged()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "${getString(R.string.fetch_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun loadMorePosts() {
        binding.btnLoadMore.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        currentOffset += pageSize

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val morePosts = api.getCreatorPosts(service, creatorId, offset = currentOffset)
                loadedPosts.addAll(morePosts)
                applySort()
                updateLoadMoreButton(morePosts.size)
            } catch (e: Exception) {
                e.printStackTrace()
                currentOffset -= pageSize
                binding.btnLoadMore.visibility = View.VISIBLE
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateLoadMoreButton(loadedCount: Int) {
        if (loadedCount >= pageSize) {
            binding.btnLoadMore.visibility = View.VISIBLE
        } else {
            binding.btnLoadMore.visibility = View.GONE
        }
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
