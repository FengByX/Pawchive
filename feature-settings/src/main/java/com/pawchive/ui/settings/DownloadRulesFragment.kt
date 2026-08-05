package com.pawchive.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.common.R
import com.pawchive.common.databinding.DialogDownloadRuleBinding
import com.pawchive.common.databinding.FragmentDownloadRulesBinding
import com.pawchive.core.model.DownloadRuleEntity
import com.pawchive.core.model.DownloadRuleFileType
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 下载规则管理页（ARCH-FEATURE-002）。
 *
 * - RecyclerView 展示规则列表（[DownloadRulesAdapter]，ListAdapter + DiffUtil）
 * - FAB 打开添加对话框；点击规则条目打开编辑对话框；开关即时启停；删除需二次确认
 * - 空状态与列表根据 ViewModel 状态切换
 */
@AndroidEntryPoint
class DownloadRulesFragment : Fragment() {

    private var _binding: FragmentDownloadRulesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DownloadRulesViewModel by viewModels()
    private lateinit var adapter: DownloadRulesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadRulesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        setupRecyclerView()
        setupFab()
        observeUiState()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = DownloadRulesAdapter(
            onEdit = { showRuleDialog(it) },
            onToggle = { rule, enabled -> viewModel.toggleEnabled(rule, enabled) },
            onDelete = { showDeleteConfirm(it) }
        )
        binding.rvDownloadRules.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDownloadRules.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddRule.setOnClickListener { showRuleDialog(null) }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.rules)
                    binding.layoutEmpty.visibility =
                        if (state.rules.isEmpty()) View.VISIBLE else View.GONE

                    state.toastMessage?.let {
                        Toast.makeText(requireContext(), R.string.download_rules_saved, Toast.LENGTH_SHORT).show()
                        viewModel.consumeToast()
                    }
                }
            }
        }
    }

    /** 新增（existing == null）或编辑（existing != null）规则对话框。 */
    private fun showRuleDialog(existing: DownloadRuleEntity?) {
        val dialogBinding = DialogDownloadRuleBinding.inflate(layoutInflater)
        val isEdit = existing != null
        if (isEdit) {
            dialogBinding.etRuleName.setText(existing.name)
            dialogBinding.etRuleCreator.setText(existing.creatorId.orEmpty())
            dialogBinding.etRuleService.setText(existing.service.orEmpty())
            checkFileTypeChip(dialogBinding, existing.fileType)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isEdit) R.string.download_rules_edit else R.string.download_rules_add)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = dialogBinding.etRuleName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.download_rules_name_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val creator = dialogBinding.etRuleCreator.text?.toString()?.trim()
                val service = dialogBinding.etRuleService.text?.toString()?.trim()
                val fileType = selectedFileType(dialogBinding)
                if (isEdit) {
                    viewModel.updateRule(existing, name, creator, service, fileType)
                } else {
                    viewModel.addRule(name, creator, service, fileType)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDeleteConfirm(rule: DownloadRuleEntity) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.download_rules_delete)
            .setMessage(R.string.download_rules_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.deleteRule(rule) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun selectedFileType(binding: DialogDownloadRuleBinding): DownloadRuleFileType = when {
        binding.chipImage.isChecked -> DownloadRuleFileType.IMAGE
        binding.chipVideo.isChecked -> DownloadRuleFileType.VIDEO
        binding.chipAttachment.isChecked -> DownloadRuleFileType.ATTACHMENT
        else -> DownloadRuleFileType.ALL
    }

    private fun checkFileTypeChip(binding: DialogDownloadRuleBinding, type: DownloadRuleFileType) {
        val chip = when (type) {
            DownloadRuleFileType.IMAGE -> binding.chipImage
            DownloadRuleFileType.VIDEO -> binding.chipVideo
            DownloadRuleFileType.ATTACHMENT -> binding.chipAttachment
            DownloadRuleFileType.ALL -> binding.chipAll
        }
        chip.isChecked = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
