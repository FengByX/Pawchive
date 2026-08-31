package com.pawchive.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.common.R
import com.pawchive.common.databinding.FragmentOfflineArchivesBinding
import com.pawchive.common.nav.AppNavigator
import com.pawchive.core.model.OfflineArchiveEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 离线归档管理页（ARCH-FEATURE-001 遗留项）。
 *
 * - 列表展示收藏帖子的离线副本（标题 / 创作者 / 收藏相对时间）
 * - SearchView 实时触发 FTS 搜索（空查询恢复全量列表）
 * - 点击行跳转帖子详情；删除按钮弹确认对话框（仅删离线副本，不影响收藏）
 * - 右上角"清空"按钮同样弹确认对话框，有数据时可见
 */
@AndroidEntryPoint
class OfflineArchivesFragment : Fragment() {

    private var _binding: FragmentOfflineArchivesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OfflineArchivesViewModel by viewModels()
    private lateinit var adapter: OfflineArchivesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOfflineArchivesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        setupSearch()
        setupClearAll()
        setupRecyclerView()
        observeUiState()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.onQueryChange(newText.orEmpty())
                return true
            }
        })
    }

    private fun setupClearAll() {
        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.offline_archives_clear_all)
                .setMessage(R.string.offline_archives_clear_all_confirm)
                .setPositiveButton(R.string.offline_archives_clear_all) { _, _ ->
                    viewModel.clearAll()
                    Toast.makeText(
                        requireContext(),
                        R.string.offline_archives_cleared,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun setupRecyclerView() {
        adapter = OfflineArchivesAdapter(
            onItemClick = { openPostDetail(it) },
            onDelete = { showDeleteConfirm(it) }
        )
        binding.rvOfflineArchives.layoutManager = LinearLayoutManager(requireContext())
        binding.rvOfflineArchives.adapter = adapter
    }

    private fun openPostDetail(item: OfflineArchiveEntity) {
        (activity as? AppNavigator)?.openPostDetail(item.service, item.creatorId, item.postId)
    }

    private fun showDeleteConfirm(item: OfflineArchiveEntity) {
        val title = item.title?.takeIf { it.isNotBlank() } ?: item.postId
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.offline_archives_delete)
            .setMessage(getString(R.string.offline_archives_delete_confirm, title))
            .setPositiveButton(R.string.offline_archives_delete) { _, _ ->
                viewModel.remove(item.service, item.creatorId, item.postId)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.archives)
                    val empty = state.archives.isEmpty()
                    binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.btnClearAll.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
