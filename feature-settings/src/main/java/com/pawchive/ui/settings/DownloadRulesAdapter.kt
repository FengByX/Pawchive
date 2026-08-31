package com.pawchive.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pawchive.common.R
import com.pawchive.common.databinding.ItemDownloadRuleBinding
import com.pawchive.core.model.DownloadRuleEntity
import com.pawchive.core.model.DownloadRuleFileType

/**
 * 下载规则列表适配器（ARCH-FEATURE-002）。
 *
 * 使用 ListAdapter + DiffUtil.ItemCallback 实现高效差量更新。
 * - 点击规则信息区域 → 编辑
 * - 开关 → 启用/停用
 * - 删除按钮 → 删除确认
 */
class DownloadRulesAdapter(
    private val onEdit: (DownloadRuleEntity) -> Unit,
    private val onToggle: (DownloadRuleEntity, Boolean) -> Unit,
    private val onDelete: (DownloadRuleEntity) -> Unit
) : ListAdapter<DownloadRuleEntity, DownloadRulesAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DownloadRuleEntity>() {
            override fun areItemsTheSame(oldItem: DownloadRuleEntity, newItem: DownloadRuleEntity): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DownloadRuleEntity, newItem: DownloadRuleEntity): Boolean =
                oldItem == newItem
        }
    }

    inner class ViewHolder(private val binding: ItemDownloadRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: DownloadRuleEntity) {
            binding.tvRuleName.text = rule.name
            binding.tvRuleCondition.text = formatCondition(rule)

            // 开关：先移除监听避免 bind 时触发回调
            binding.switchRuleEnabled.setOnCheckedChangeListener(null)
            binding.switchRuleEnabled.isChecked = rule.enabled
            binding.switchRuleEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(rule, isChecked)
            }

            binding.layoutRuleInfo.setOnClickListener { onEdit(rule) }
            binding.btnDeleteRule.setOnClickListener { onDelete(rule) }
        }

        /** 拼接规则条件描述，如 "creatorId · service · 图片"；无 creator/service 时仅显示文件类型。 */
        private fun formatCondition(rule: DownloadRuleEntity): String {
            val ctx = binding.root.context
            val parts = buildList {
                rule.creatorId?.let(::add)
                rule.service?.let(::add)
                add(
                    when (rule.fileType) {
                        DownloadRuleFileType.ALL -> ctx.getString(R.string.download_rules_all_types)
                        DownloadRuleFileType.IMAGE -> ctx.getString(R.string.download_rules_image)
                        DownloadRuleFileType.VIDEO -> ctx.getString(R.string.download_rules_video)
                        DownloadRuleFileType.ATTACHMENT -> ctx.getString(R.string.download_rules_attachment)
                    }
                )
            }
            return parts.joinToString(" · ")
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemDownloadRuleBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
