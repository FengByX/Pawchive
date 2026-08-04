package com.pawchive.ui.settings

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.pawchive.R
import com.pawchive.data.SettingsManager
import com.pawchive.databinding.FragmentSettingsBinding
import com.pawchive.ui.MainActivity
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    // 缓存清理 loading Toast 引用，由 isCleaningCache 状态控制显示/取消
    private var cleaningToast: Toast? = null

    private val pickDownloadLocation = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setDownloadTreeUri(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackButton()
        setupToggleButtonColors()
        setupLanguage()
        setupAppearance()
        setupBlockedCreators()
        setupDownloadLocation()
        setupAutoCleanCache()
        setupManualCleanCache()
        setupAutoCheckUpdate()
        setupTelegramButton()
        observeUiState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshCacheSize()
        viewModel.refreshDownloadLocationText()
    }

    /**
     * 订阅 ViewModel 状态，渲染屏蔽计数、下载位置、缓存大小、版本号、Toast 等（P2 FRONTEND-008）。
     * - 语言/外观/开关：初始值在 setup 中从 UiState 读取，避免双向同步循环
     * - 屏蔽计数、下载位置文本、缓存大小、版本号：由 UiState 驱动
     * - isCleaningCache：控制 loading Toast 的显示与取消
     * - toastMessage：一次性 Toast，展示后清除
     */
    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    // 屏蔽计数
                    binding.tvBlockedCount.text = if (state.blockedCount == 0) {
                        getString(R.string.blocked_count_zero)
                    } else {
                        getString(R.string.blocked_count, state.blockedCount)
                    }

                    // 下载位置
                    binding.tvDownloadLocation.text = state.downloadLocationText

                    // 缓存大小
                    binding.tvCacheSize.text = state.cacheSizeText

                    // 版本号（仅展示语义化版本号，不暴露 versionCode）
                    binding.tvVersion.text = "v${state.versionName}"

                    // 缓存清理 loading Toast
                    if (state.isCleaningCache) {
                        if (cleaningToast == null) {
                            cleaningToast = Toast.makeText(
                                requireContext(),
                                R.string.cache_cleaning,
                                Toast.LENGTH_SHORT
                            )
                            cleaningToast?.show()
                        }
                    } else {
                        cleaningToast?.cancel()
                        cleaningToast = null
                    }

                    // 一次性 Toast
                    state.toastMessage?.let { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearToast()
                    }
                }
            }
        }
    }

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupToggleButtonColors() {
        val primaryContainer = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val onSurface = getThemeColor(com.google.android.material.R.attr.colorOnSurface)
        val outline = getThemeColor(com.google.android.material.R.attr.colorOutline)

        val toggleGroups = listOf(binding.toggleLanguage, binding.toggleAppearance)
        for (group in toggleGroups) {
            for (i in 0 until group.childCount) {
                val button = group.getChildAt(i) as? MaterialButton ?: continue
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                )
                val bgColors = intArrayOf(primaryContainer, android.graphics.Color.TRANSPARENT)
                val textColors = intArrayOf(onPrimaryContainer, onSurface)
                val strokeColors = intArrayOf(primaryContainer, outline)

                button.backgroundTintList = ColorStateList(states, bgColors)
                button.setTextColor(ColorStateList(states, textColors))
                button.strokeColor = ColorStateList(states, strokeColors)
            }
        }
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        requireContext().theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun setupLanguage() {
        val langId = when (viewModel.uiState.value.language) {
            SettingsManager.Language.CHINESE -> R.id.btn_lang_zh
            SettingsManager.Language.ENGLISH -> R.id.btn_lang_en
            SettingsManager.Language.JAPANESE -> R.id.btn_lang_ja
        }
        binding.toggleLanguage.check(langId)

        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.btn_lang_zh -> SettingsManager.Language.CHINESE
                R.id.btn_lang_en -> SettingsManager.Language.ENGLISH
                R.id.btn_lang_ja -> SettingsManager.Language.JAPANESE
                else -> return@addOnButtonCheckedListener
            }
            if (language == viewModel.uiState.value.language) return@addOnButtonCheckedListener
            viewModel.setLanguage(language)
            (activity as? MainActivity)?.restartForLanguageChange()
        }
    }

    private fun setupAppearance() {
        val appearanceId = when (viewModel.uiState.value.appearance) {
            SettingsManager.Appearance.LIGHT -> R.id.btn_appearance_light
            SettingsManager.Appearance.DARK -> R.id.btn_appearance_dark
            SettingsManager.Appearance.FOLLOW_SYSTEM -> R.id.btn_appearance_system
        }
        binding.toggleAppearance.check(appearanceId)

        binding.toggleAppearance.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val appearance = when (checkedId) {
                R.id.btn_appearance_light -> SettingsManager.Appearance.LIGHT
                R.id.btn_appearance_dark -> SettingsManager.Appearance.DARK
                R.id.btn_appearance_system -> SettingsManager.Appearance.FOLLOW_SYSTEM
                else -> return@addOnButtonCheckedListener
            }
            if (appearance == viewModel.uiState.value.appearance) return@addOnButtonCheckedListener
            viewModel.setAppearance(appearance)
        }
    }

    private fun setupBlockedCreators() {
        binding.btnManageBlocked.setOnClickListener {
            showBlockedCreatorsDialog()
        }
    }

    private fun showBlockedCreatorsDialog() {
        val blocked = viewModel.getBlockedCreators()
        if (blocked.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_blocked_creators, Toast.LENGTH_SHORT).show()
            return
        }

        val items = blocked.map { "${it.first} | ${it.second}" }.toTypedArray()
        val checkedItems = BooleanArray(items.size) { true }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.blocked_creators)
            .setMultiChoiceItems(items, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(R.string.done) { _, _ ->
                val toUnblock = mutableListOf<Pair<String, String>>()
                for (i in items.indices) {
                    if (!checkedItems[i]) {
                        toUnblock.add(blocked[i])
                    }
                }
                if (toUnblock.isNotEmpty()) {
                    viewModel.unblockCreators(toUnblock)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupDownloadLocation() {
        binding.btnChangeLocation.setOnClickListener {
            openSystemFilePicker()
        }
    }

    private fun openSystemFilePicker() {
        try {
            pickDownloadLocation.launch(null)
        } catch (_: Exception) {
            Toast.makeText(
                requireContext(),
                R.string.file_picker_not_available,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupAutoCleanCache() {
        binding.switchAutoClean.isChecked = viewModel.uiState.value.autoCleanCacheEnabled
        binding.switchAutoClean.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCleanCacheEnabled(isChecked)
        }
    }

    private fun setupManualCleanCache() {
        binding.btnCleanCache.setOnClickListener {
            viewModel.cleanCache()
        }
    }

    private fun setupAutoCheckUpdate() {
        binding.switchAutoCheckUpdate.isChecked = viewModel.uiState.value.autoCheckUpdateEnabled
        binding.switchAutoCheckUpdate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCheckUpdateEnabled(isChecked)
        }
    }

    private fun setupTelegramButton() {
        binding.btnJoinTelegram.setOnClickListener {
            val url = getString(R.string.telegram_channel_url)
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(
                    requireContext(),
                    R.string.browser_not_available,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleaningToast?.cancel()
        cleaningToast = null
        _binding = null
    }
}
