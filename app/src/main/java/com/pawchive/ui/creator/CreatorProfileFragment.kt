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

    private lateinit var postAdapter: PostAdapter
    private lateinit var bookmarkManager: BookmarkManager
    private lateinit var blockedCreatorManager: BlockedCreatorManager
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
        blockedCreatorManager = BlockedCreatorManager.getInstance(requireContext())
        authRepository = AuthRepository(requireContext())

        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupRecyclerView()
        setupBookmarkButton()
        setupBlockButton()
        setupLoadMoreButton()
        setupSortButton()
        loadCreatorDetails()
        loadCreatorAnnouncements()
        loadCreatorLinks()
        loadCreatorPosts()
    }
