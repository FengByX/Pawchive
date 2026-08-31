package com.pawchive.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.pawchive.common.databinding.FragmentContentUpdatesBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.data.repository.ContentUpdateWithCreator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 内容更新页（ARCH-FEATURE-003）。
 *
 * - RecyclerView 展示订阅创作者的新帖通知（未读圆点 + 相对时间）
 * - 点击条目：标记已读 + 跳转帖子详情
 * - 顶部"全部已读"按钮：清空未读标记
 */
@AndroidEntryPoint
class ContentUpdatesFragment : Fragment() {

    private var _binding: FragmentContentUpdatesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ContentUpdatesViewModel by viewModels()
    private lateinit var adapter: ContentUpdatesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContentUpdatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        setupRecyclerView()
        setupMarkAllRead()
        observeUiState()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = ContentUpdatesAdapter(onItemClick = ::openUpdate)
        binding.rvContentUpdates.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContentUpdates.adapter = adapter
    }

    private fun setupMarkAllRead() {
        binding.btnMarkAllRead.setOnClickListener {
            viewModel.markAllRead()
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.updates)
                    binding.layoutEmpty.visibility =
                        if (state.updates.isEmpty()) View.VISIBLE else View.GONE
                    binding.btnMarkAllRead.visibility =
                        if (state.updates.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    /** 打开更新条目对应的帖子详情，并先标记该条已读。 */
    private fun openUpdate(item: ContentUpdateWithCreator) {
        viewModel.markRead(item.update.id)
        (activity as? AppNavigator)?.openPostDetail(
            item.update.service,
            item.update.creatorId,
            item.update.postId
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
