package com.pawchive.ui.settings

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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.common.R
import com.pawchive.common.databinding.FragmentCacheManagerBinding
import com.pawchive.core.store.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 存储空间与缓存管理页（ARCH-FEATURE-004）。
 *
 * - 汇总卡片：可清理缓存总量、阈值提醒、上次清理时间
 * - 分类行：图片缓存 / 其他缓存 / 离线归档 / 下载文件，各自展示大小并可单独清理
 * - 离线归档与下载文件删除为破坏性操作，弹确认对话框二次确认
 * - 清理期间顶部进度条可见，防止重复触发
 */
@AndroidEntryPoint
class CacheManagerFragment : Fragment() {

    private var _binding: FragmentCacheManagerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: CacheManagerViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCacheManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        setupClearButtons()
        observeUiState()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupClearButtons() {
        binding.btnClearImage.setOnClickListener {
            viewModel.clearCategory(CacheCategory.IMAGE)
        }
        binding.btnClearOther.setOnClickListener {
            viewModel.clearCategory(CacheCategory.OTHER)
        }
        binding.btnClearArchive.setOnClickListener {
            showConfirmDialog(
                title = R.string.cache_archive,
                message = R.string.cache_clear_archive_confirm
            ) { viewModel.clearCategory(CacheCategory.ARCHIVE) }
        }
        binding.btnClearDownloads.setOnClickListener {
            showConfirmDialog(
                title = R.string.cache_downloads,
                message = R.string.cache_clear_downloads_confirm
            ) { viewModel.clearCategory(CacheCategory.DOWNLOADS) }
        }
    }

    private fun showConfirmDialog(title: Int, message: Int, onConfirm: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.cache_clear) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(state)
                }
            }
        }
    }

    private fun render(state: CacheManagerUiState) {
        // 统计中/清理中进度
        binding.progressLoading.visibility =
            if (state.isLoading || state.cleaningCategory != null) View.VISIBLE else View.GONE

        // 汇总
        binding.tvTotalSize.text = SettingsManager.formatSize(state.totalClearable)

        // 阈值提醒（图片 + 其他缓存口径，与自动清理一致）
        if (state.isThresholdExceeded) {
            binding.tvThresholdWarning.text = getString(
                R.string.cache_threshold_warning,
                SettingsManager.formatSize(state.thresholdBytes)
            )
            binding.tvThresholdWarning.visibility = View.VISIBLE
            binding.dividerAfterWarning.visibility = View.VISIBLE
        } else {
            binding.tvThresholdWarning.visibility = View.GONE
            binding.dividerAfterWarning.visibility = View.GONE
        }

        // 分类
        binding.tvImageSize.text = SettingsManager.formatSize(state.imageCacheSize)
        binding.tvOtherSize.text = SettingsManager.formatSize(state.otherCacheSize)
        binding.tvArchiveSize.text = SettingsManager.formatSize(state.archiveBytes)
        binding.tvArchiveDesc.text = getString(R.string.cache_archive_desc, state.archiveCount)
        binding.tvDownloadsSize.text = SettingsManager.formatSize(state.downloadFilesSize)

        // 上次清理时间
        binding.tvLastClean.text = if (state.lastCleanTime > 0) {
            getString(R.string.cache_last_clean, formatTimestamp(state.lastCleanTime))
        } else {
            getString(R.string.cache_never_cleaned)
        }

        // 一次性 Toast
        state.toastMessage?.let {
            Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show()
            viewModel.consumeToast()
        }
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
