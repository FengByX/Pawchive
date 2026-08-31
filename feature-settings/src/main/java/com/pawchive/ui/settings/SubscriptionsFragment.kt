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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pawchive.common.R
import com.pawchive.core.model.CreatorSubscriptionEntity
import com.pawchive.common.databinding.FragmentSubscriptionsBinding
import com.pawchive.common.nav.AppNavigator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * 订阅管理页（ARCH-FEATURE-003 遗留项）。
 *
 * - RecyclerView 展示全部订阅（平台徽标 + 创作者名 + 订阅时间），订阅时间倒序
 * - 点击行跳转创作者主页；退订按钮弹确认对话框（退订同时清除该创作者历史通知）
 * - 空状态提示去创作者主页订阅
 */
@AndroidEntryPoint
class SubscriptionsFragment : Fragment() {

    private var _binding: FragmentSubscriptionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SubscriptionsViewModel by viewModels()
    private lateinit var adapter: SubscriptionsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBackButton()
        setupRecyclerView()
        observeUiState()
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = SubscriptionsAdapter(
            onItemClick = { item ->
                (activity as? AppNavigator)?.openCreatorProfile(item.service, item.creatorId)
            },
            onUnsubscribe = { showUnsubscribeConfirm(it) }
        )
        binding.rvSubscriptions.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSubscriptions.adapter = adapter
    }

    private fun showUnsubscribeConfirm(item: CreatorSubscriptionEntity) {
        val displayName = item.name?.takeIf { it.isNotBlank() } ?: item.creatorId
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.subscriptions_unsubscribe)
            .setMessage(getString(R.string.subscriptions_unsubscribe_confirm, displayName))
            .setPositiveButton(R.string.subscriptions_unsubscribe) { _, _ ->
                viewModel.unsubscribe(item.service, item.creatorId)
                Toast.makeText(
                    requireContext(),
                    R.string.subscriptions_unsubscribed,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    adapter.submitList(state.subscriptions)
                    binding.layoutEmpty.visibility =
                        if (state.subscriptions.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
