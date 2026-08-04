package com.pawchive.ui.downloads

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.R
import com.pawchive.data.model.DownloadStatus
import com.pawchive.databinding.FragmentDownloadsBinding
import com.pawchive.ui.MainActivity
import com.pawchive.ui.adapter.DownloadHistoryAdapter
import kotlinx.coroutines.launch

/**
 * 下载中心 Fragment（FEATURE-001）。
 *
 * 采用 ViewModel + UiState 架构，订阅下载历史 Flow 自动刷新列表，
 * 支持状态过滤、取消、重试、打开、分享、删除操作。
 */
class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DownloadsViewModel by viewModels()

    private lateinit var adapter: DownloadHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupFilterChips()
        setupSwipeRefresh()
        setupToolbar()

        observeUiState()
    }

    private fun setupRecyclerView() {
        adapter = DownloadHistoryAdapter(
            onCancel = { record ->
                viewModel.cancelDownload(record.url, record.fileName, record.mimeType, record.type)
                Toast.makeText(context, R.string.download_cancelled, Toast.LENGTH_SHORT).show()
            },
            onRetry = { record ->
                viewModel.retryDownload(record.url, record.fileName, record.mimeType, record.type)
                Toast.makeText(context, R.string.download_retried, Toast.LENGTH_SHORT).show()
            },
            onOpen = { record -> viewModel.openFile(record.filePath) },
            onShare = { record -> viewModel.shareFile(record.filePath) },
            onDelete = { record -> viewModel.removeRecord(record.url) }
        )
        binding.rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloads.adapter = adapter
    }

    private fun setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { group, checkedIds ->
            val status = when {
                checkedIds.isEmpty() -> null
                checkedIds.contains(R.id.chipRunning) -> DownloadStatus.RUNNING
                checkedIds.contains(R.id.chipCompleted) -> DownloadStatus.COMPLETED
                checkedIds.contains(R.id.chipFailed) -> DownloadStatus.FAILED
                else -> null
            }
            viewModel.setFilter(status)
        }
    }

    private fun setupSwipeRefresh() {
        // 历史记录通过 Flow 自动刷新，下拉刷新仅做视觉反馈
        binding.swipeRefresh.setOnRefreshListener {
            binding.swipeRefresh.isRefreshing = false
        }
        binding.swipeRefresh.setColorSchemeColors(
            getThemeColor(com.google.android.material.R.attr.colorPrimary)
        )
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            (activity as? MainActivity)?.navigateToMainTab(R.id.navigation_home)
        }
        binding.btnClearAll.setOnClickListener { showClearAllDialog() }
    }

    private fun showClearAllDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.clear_history)
            .setMessage(R.string.confirm_clear_history)
            .setPositiveButton(R.string.clear_history) { _, _ ->
                viewModel.clearAllHistory()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.records)
                    val empty = state.records.isEmpty()
                    binding.layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.swipeRefresh.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
