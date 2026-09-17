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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.pawchive.common.R
import com.pawchive.core.store.SettingsManager
import com.pawchive.common.databinding.FragmentSettingsBinding
import com.pawchive.common.nav.AppNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
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

    private val pickAutoBackupLocation = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setAutoBackupTreeUri(uri)
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
        setupDisplayScale()
        setupBlockedCreators()
        setupDownloadLocation()
        setupAutoCleanCache()
        setupManualCleanCache()
        setupCacheManagerEntry()
        setupAutoCheckUpdate()
        setupCrashLog()
        setupHideBookmarkedCreators()
        setupDedupeByCreator()
        setupStartupTab()
        setupAutoSubscribeOnBookmark()
        setupDownloadRules()
        setupContentUpdates()
        setupSubscriptions()
        setupBackup()
        setupOfflineArchives()
        setupTelegramButton()
        // FEATURE 设置项扩展（批次一）
        setupDownloadCompleteNotification()
        setupMediaPlayback()
        setupNewPostNotification()
        setupSyncInterval()
        setupHomeDefaultSort()
        setupSearchHistoryLimit()
        setupUpdateFrequency()
        // FEATURE 设置项扩展（批次二：下载参数）
        setupDownloadParams()
        // FEATURE 设置项扩展（批次三）
        setupFilenameFormat()
        setupAccentTheme()
        setupReduceAnimations()
        setupGridColumns()
        setupUpdateChannel()
        setupAutoBackup()
        setupWipeData()
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

                    // 内容更新未读徽标（ARCH-FEATURE-003）
                    if (state.unreadCount > 0) {
                        binding.tvUpdatesBadge.text = getString(R.string.content_updates_unread_count, state.unreadCount)
                        binding.tvUpdatesBadge.visibility = View.VISIBLE
                    } else {
                        binding.tvUpdatesBadge.visibility = View.GONE
                    }

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
            SettingsManager.Language.SYSTEM -> R.id.btn_lang_system
            SettingsManager.Language.CHINESE -> R.id.btn_lang_zh
            SettingsManager.Language.ENGLISH -> R.id.btn_lang_en
            SettingsManager.Language.JAPANESE -> R.id.btn_lang_ja
        }
        binding.toggleLanguage.check(langId)

        binding.toggleLanguage.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.btn_lang_system -> SettingsManager.Language.SYSTEM
                R.id.btn_lang_zh -> SettingsManager.Language.CHINESE
                R.id.btn_lang_en -> SettingsManager.Language.ENGLISH
                R.id.btn_lang_ja -> SettingsManager.Language.JAPANESE
                else -> return@addOnButtonCheckedListener
            }
            if (language == viewModel.uiState.value.language) return@addOnButtonCheckedListener
            viewModel.setLanguage(language)
            (activity as? AppNavigator)?.restartForLanguageChange()
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

    /**
     * 显示与缩放（FEATURE）：整体 UI 缩放 + 全局文字大小两个连续滑杆。
     *
     * 交互设计：
     * - 拖动中：数值文本与 Slider 自带的 floating label 实时刷新（内存操作，不落盘）；
     * - 松手：持久化（DataStore + 启动缓存双写）并重建 Activity 使缩放全局即时生效——
     *   View 体系无法在拖动中热替换全局密度/字号，Activity 重建（带淡入淡出动画）
     *   是"调整后界面即时生效"的标准实现；
     * - 恢复默认：滑杆回 1.0 并走同一条提交流程；若当前已是默认值则不重建（无闪烁）。
     */
    private fun setupDisplayScale() {
        val state = viewModel.uiState.value
        binding.sliderUiScale.value = state.uiScale.coerceIn(
            SettingsManager.SCALE_MIN, SettingsManager.SCALE_MAX
        )
        binding.sliderFontScale.value = state.fontScale.coerceIn(
            SettingsManager.SCALE_MIN, SettingsManager.SCALE_MAX
        )
        refreshScaleValueLabels()

        binding.sliderUiScale.setLabelFormatter { formatScale(it) }
        binding.sliderFontScale.setLabelFormatter { formatScale(it) }

        binding.sliderUiScale.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.tvUiScaleValue.text = formatScale(value)
        }
        binding.sliderFontScale.addOnChangeListener { _, value, fromUser ->
            if (fromUser) binding.tvFontScaleValue.text = formatScale(value)
        }

        binding.sliderUiScale.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                commitScale(slider.value, isUiScale = true)
            }
        })
        binding.sliderFontScale.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                commitScale(slider.value, isUiScale = false)
            }
        })

        binding.btnResetUiScale.setOnClickListener {
            binding.sliderUiScale.value = SettingsManager.DEFAULT_SCALE
            commitScale(SettingsManager.DEFAULT_SCALE, isUiScale = true)
        }
        binding.btnResetFontScale.setOnClickListener {
            binding.sliderFontScale.value = SettingsManager.DEFAULT_SCALE
            commitScale(SettingsManager.DEFAULT_SCALE, isUiScale = false)
        }
    }

    private fun formatScale(value: Float): String =
        getString(R.string.scale_value_percent, (value * 100).roundToInt())

    private fun refreshScaleValueLabels() {
        binding.tvUiScaleValue.text = formatScale(binding.sliderUiScale.value)
        binding.tvFontScaleValue.text = formatScale(binding.sliderFontScale.value)
    }

    /**
     * 落盘并按需重建。与提交前应用中生效的值（[SettingsUiState] 里的快照）
     * 相同则跳过重建，避免拖动后未产生实际变化时的无意义重启。
     */
    private fun commitScale(value: Float, isUiScale: Boolean) {
        val applied = if (isUiScale) {
            viewModel.uiState.value.uiScale
        } else {
            viewModel.uiState.value.fontScale
        }
        if (isUiScale) viewModel.setUiScale(value) else viewModel.setFontScale(value)
        if (kotlin.math.abs(value - applied) > 0.001f) {
            // 复用语言切换的重建路径（finish + startActivity + 淡入淡出），
            // attachBaseContext 会从启动缓存读回新缩放值并覆盖 Configuration
            (activity as? AppNavigator)?.restartForLanguageChange()
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

    /** 缓存管理页入口（ARCH-FEATURE-004）。 */
    private fun setupCacheManagerEntry() {
        binding.rowCacheManager.setOnClickListener {
            (activity as? AppNavigator)?.openFragment(CacheManagerFragment())
        }
    }

    private fun setupAutoCheckUpdate() {
        binding.switchAutoCheckUpdate.isChecked = viewModel.uiState.value.autoCheckUpdateEnabled
        binding.switchAutoCheckUpdate.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoCheckUpdateEnabled(isChecked)
        }
    }

    /**
     * 崩溃日志查看入口（FEATURE-006）：读取 CrashHandler 落盘的崩溃记录，
     * 弹窗展示堆栈，便于用户反馈真实崩溃原因。
     */
    private fun setupCrashLog() {
        binding.rowCrashLog.setOnClickListener {
            val logs = com.pawchive.core.util.CrashHandler.getCrashLogs(requireContext())
            if (logs.isEmpty()) {
                Toast.makeText(requireContext(), R.string.crash_log_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val names = logs.map { file ->
                file.name.removePrefix("crash_").removeSuffix(".txt")
            }.toTypedArray()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.crash_log_select)
                .setItems(names) { dialog, which ->
                    showCrashLogContent(logs[which])
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun showCrashLogContent(file: java.io.File) {
        viewLifecycleOwner.lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { file.readText() }.getOrElse { it.message ?: "read failed" }
            }
            val scroll = android.widget.ScrollView(requireContext())
            val textView = TextView(requireContext()).apply {
                text = content
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(24, 24, 24, 24)
                setTextColor(resources.getColor(R.color.text_primary, null))
            }
            scroll.addView(textView)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(file.name)
                .setView(scroll)
                .setNeutralButton(R.string.copy) { _, _ -> copyCrashLogToClipboard(content) }
                .setPositiveButton(R.string.share) { _, _ -> shareCrashLog(file, content) }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /** 将崩溃日志文本复制到系统剪贴板，便于用户粘贴反馈。 */
    private fun copyCrashLogToClipboard(content: String) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("crash_log", content))
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    /** 通过系统分享面板导出崩溃日志：优先以文件形式分享（FileProvider），失败则退化为纯文本分享。 */
    private fun shareCrashLog(file: java.io.File, content: String) {
        val context = requireContext()
        val chooserTitle = getString(R.string.crash_log_share_title)
        val shareIntent = runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_TEXT, content)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrElse {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(Intent.EXTRA_TEXT, content)
            }
        }
        runCatching {
            startActivity(Intent.createChooser(shareIntent, chooserTitle))
        }.onFailure {
            // 无可用分享目标时退化为复制，确保导出路径始终可用
            copyCrashLogToClipboard(content)
        }
    }

    private fun setupHideBookmarkedCreators() {
        binding.switchHideBookmarkedCreators.isChecked =
            viewModel.uiState.value.hideBookmarkedCreatorsEnabled
        binding.switchHideBookmarkedCreators.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setHideBookmarkedCreatorsEnabled(isChecked)
        }
    }

    /** 首页同作者仅显示一条开关（FEATURE）。 */
    private fun setupDedupeByCreator() {
        binding.switchDedupeByCreator.isChecked =
            viewModel.uiState.value.dedupeByCreatorEnabled
        binding.switchDedupeByCreator.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDedupeByCreatorEnabled(isChecked)
        }
    }

    /** 启动主界面选择（FEATURE）：点击弹出选项，选择后立即生效。 */
    private fun setupStartupTab() {
        refreshStartupTabValue()
        binding.rowStartupTab.setOnClickListener {
            val tabs = SettingsManager.StartupTab.entries
            val labels = tabs.map { getStartupTabLabel(it) }.toTypedArray()
            val currentIndex = tabs.indexOf(viewModel.uiState.value.startupTab)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.startup_tab)
                .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                    viewModel.setStartupTab(tabs[which])
                    refreshStartupTabValue()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun refreshStartupTabValue() {
        binding.tvStartupTabValue.text =
            getStartupTabLabel(viewModel.uiState.value.startupTab)
    }

    private fun getStartupTabLabel(tab: SettingsManager.StartupTab): String {
        return when (tab) {
            SettingsManager.StartupTab.HOME -> getString(R.string.tab_home)
            SettingsManager.StartupTab.SEARCH -> getString(R.string.tab_search)
            SettingsManager.StartupTab.BOOKMARKS -> getString(R.string.tab_bookmarks)
            SettingsManager.StartupTab.ACCOUNT -> getString(R.string.tab_account)
            SettingsManager.StartupTab.DOWNLOADS -> getString(R.string.tab_downloads)
        }
    }

    /** 收藏创作者时自动订阅开关（ARCH-FEATURE-003 联动遗留项）。 */
    private fun setupAutoSubscribeOnBookmark() {
        binding.switchAutoSubscribeOnBookmark.isChecked =
            viewModel.uiState.value.autoSubscribeOnBookmarkEnabled
        binding.switchAutoSubscribeOnBookmark.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setAutoSubscribeOnBookmarkEnabled(isChecked)
        }
    }

    private fun setupDownloadRules() {
        binding.rowDownloadRules.setOnClickListener {
            (activity as? AppNavigator)?.openFragment(DownloadRulesFragment())
        }
    }

    private fun setupContentUpdates() {
        binding.rowContentUpdates.setOnClickListener {
            (activity as? AppNavigator)?.openFragment(ContentUpdatesFragment())
        }
    }

    /** 订阅管理页入口（ARCH-FEATURE-003 遗留项）。 */
    private fun setupSubscriptions() {
        binding.rowSubscriptions.setOnClickListener {
            (activity as? AppNavigator)?.openFragment(SubscriptionsFragment())
        }
    }

    /** 备份与迁移页入口（ARCH-FEATURE-005）。 */
    private fun setupBackup() {
        binding.rowBackup.setOnClickListener {
            (activity as? AppNavigator)?.openFragment(BackupFragment())
        }
    }

    /** 离线归档管理页入口（ARCH-FEATURE-001 遗留项）。 */
    private fun setupOfflineArchives() {
        binding.rowOfflineArchives.setOnClickListener {
            (activity as? AppNavigator)?.openFragment(OfflineArchivesFragment())
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

    // ================================================================ FEATURE 设置项扩展（批次一）

    /** 下载完成通知开关。 */
    private fun setupDownloadCompleteNotification() {
        binding.switchDownloadCompleteNotification.isChecked =
            viewModel.uiState.value.downloadCompleteNotificationEnabled
        binding.switchDownloadCompleteNotification.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setDownloadCompleteNotificationEnabled(isChecked)
        }
    }

    /** 媒体播放组：断点续播 / 记住上次倍速 / 列表加载原图。 */
    private fun setupMediaPlayback() {
        binding.switchVideoResume.isChecked = viewModel.uiState.value.videoResumeEnabled
        binding.switchVideoResume.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setVideoResumeEnabled(isChecked)
        }
        binding.switchRememberSpeed.isChecked = viewModel.uiState.value.rememberPlaybackSpeedEnabled
        binding.switchRememberSpeed.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setRememberPlaybackSpeedEnabled(isChecked)
        }
        binding.switchListOriginalImage.isChecked = viewModel.uiState.value.listOriginalImageEnabled
        binding.switchListOriginalImage.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setListOriginalImageEnabled(isChecked)
        }
    }

    /** 新帖通知开关。 */
    private fun setupNewPostNotification() {
        binding.switchNewPostNotification.isChecked =
            viewModel.uiState.value.newPostNotificationEnabled
        binding.switchNewPostNotification.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setNewPostNotificationEnabled(isChecked)
        }
    }

    /** 新帖同步周期（单选弹窗，WorkManager 最小 15 分钟）。 */
    private fun setupSyncInterval() {
        refreshSyncIntervalValue()
        binding.rowSyncInterval.setOnClickListener {
            val options = arrayOf(
                getString(R.string.sync_interval_15),
                getString(R.string.sync_interval_30),
                getString(R.string.sync_interval_60)
            )
            val values = intArrayOf(15, 30, 60)
            val checked = values.indexOf(viewModel.uiState.value.syncIntervalMinutes).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sync_interval_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setSyncIntervalMinutes(values[which])
                    refreshSyncIntervalValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshSyncIntervalValue() {
        binding.tvSyncIntervalValue.text = when (viewModel.uiState.value.syncIntervalMinutes) {
            15 -> getString(R.string.sync_interval_15)
            60 -> getString(R.string.sync_interval_60)
            else -> getString(R.string.sync_interval_30)
        }
    }

    /**
     * 首页默认排序（单选弹窗）。
     * 键与 feature-home 的 HomePostSortOption.name 对齐（跨 feature 模块不直接依赖）。
     */
    private fun setupHomeDefaultSort() {
        refreshHomeSortValue()
        binding.rowHomeDefaultSort.setOnClickListener {
            val keys = listOf(
                "NEWEST_PUBLISHED", "OLDEST_PUBLISHED", "NEWEST_EDITED", "OLDEST_EDITED"
            )
            val labels = listOf(
                R.string.sort_newest_published,
                R.string.sort_oldest_published,
                R.string.sort_newest_edited,
                R.string.sort_oldest_edited
            )
            val options = labels.map { getString(it) }.toTypedArray()
            val current = viewModel.uiState.value.homeDefaultSort
            val checked = keys.indexOf(current).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.home_default_sort_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setHomeDefaultSort(keys[which])
                    refreshHomeSortValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshHomeSortValue() {
        val labels = listOf(
            R.string.sort_newest_published,
            R.string.sort_oldest_published,
            R.string.sort_newest_edited,
            R.string.sort_oldest_edited
        )
        val current = viewModel.uiState.value.homeDefaultSort
        val index = listOf(
            "NEWEST_PUBLISHED", "OLDEST_PUBLISHED", "NEWEST_EDITED", "OLDEST_EDITED"
        ).indexOf(current).takeIf { it >= 0 } ?: 0
        binding.tvHomeDefaultSortValue.text = getString(labels[index])
    }

    /** 搜索历史保留条数（单选弹窗：10 / 30 / 50）。 */
    private fun setupSearchHistoryLimit() {
        refreshSearchHistoryLimitValue()
        binding.rowSearchHistoryLimit.setOnClickListener {
            val values = intArrayOf(10, 30, 50)
            val options = values.map { getString(R.string.history_limit_value_fmt, it) }.toTypedArray()
            val current = viewModel.uiState.value.searchHistoryLimit
            val checked = values.indexOf(current).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.search_history_limit_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setSearchHistoryLimit(values[which])
                    refreshSearchHistoryLimitValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshSearchHistoryLimitValue() {
        binding.tvSearchHistoryLimitValue.text = getString(
            R.string.history_limit_value_fmt,
            viewModel.uiState.value.searchHistoryLimit
        )
    }

    /** 更新检查频率（单选弹窗；仅在自动检查更新开启时生效）。 */
    private fun setupUpdateFrequency() {
        refreshUpdateFrequencyValue()
        binding.rowUpdateFrequency.setOnClickListener {
            val values = listOf(
                SettingsManager.UpdateFrequency.EVERY_LAUNCH,
                SettingsManager.UpdateFrequency.DAILY,
                SettingsManager.UpdateFrequency.WEEKLY
            )
            val options = arrayOf(
                getString(R.string.update_freq_every_launch),
                getString(R.string.update_freq_daily),
                getString(R.string.update_freq_weekly)
            )
            val checked = values.indexOf(viewModel.uiState.value.updateFrequency).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.update_frequency_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setUpdateFrequency(values[which])
                    refreshUpdateFrequencyValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshUpdateFrequencyValue() {
        binding.tvUpdateFrequencyValue.text = when (viewModel.uiState.value.updateFrequency) {
            SettingsManager.UpdateFrequency.EVERY_LAUNCH -> getString(R.string.update_freq_every_launch)
            SettingsManager.UpdateFrequency.WEEKLY -> getString(R.string.update_freq_weekly)
            SettingsManager.UpdateFrequency.DAILY -> getString(R.string.update_freq_daily)
        }
    }

    /**
     * 批次二：下载参数（仅 Wi-Fi 开关 + 并发数/重试次数单选弹窗）。
     * 上限由 SettingsManager 钳制：并发 1–5（服务器合规）、重试 1–3。
     */
    private fun setupDownloadParams() {
        binding.switchWifiOnlyDownload.isChecked = viewModel.uiState.value.wifiOnlyDownloadEnabled
        binding.switchWifiOnlyDownload.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setWifiOnlyDownloadEnabled(isChecked)
        }
        refreshMaxConcurrentValue()
        refreshDownloadRetryValue()

        binding.rowMaxConcurrent.setOnClickListener {
            val values = intArrayOf(1, 2, 3, 4, 5)
            val options = values.map { getString(R.string.max_concurrent_value_fmt, it) }.toTypedArray()
            val current = viewModel.uiState.value.maxConcurrentDownloads
            val checked = values.indexOf(current).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.max_concurrent_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setMaxConcurrentDownloads(values[which])
                    refreshMaxConcurrentValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.rowDownloadRetry.setOnClickListener {
            val values = intArrayOf(1, 2, 3)
            val options = values.map { getString(R.string.download_retry_value_fmt, it) }.toTypedArray()
            val current = viewModel.uiState.value.downloadMaxRetry
            val checked = values.indexOf(current).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.download_retry_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setDownloadMaxRetry(values[which])
                    refreshDownloadRetryValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshMaxConcurrentValue() {
        binding.tvMaxConcurrentValue.text = getString(
            R.string.max_concurrent_value_fmt,
            viewModel.uiState.value.maxConcurrentDownloads
        )
    }

    private fun refreshDownloadRetryValue() {
        binding.tvDownloadRetryValue.text = getString(
            R.string.download_retry_value_fmt,
            viewModel.uiState.value.downloadMaxRetry
        )
    }

    // ================================================================ FEATURE 设置项扩展（批次三）

    /** 下载文件命名格式（单选弹窗）。 */
    private fun setupFilenameFormat() {
        refreshFilenameFormatValue()
        binding.rowFilenameFormat.setOnClickListener {
            val values = listOf(
                SettingsManager.FilenameFormat.ORIGINAL,
                SettingsManager.FilenameFormat.TIMESTAMP,
                SettingsManager.FilenameFormat.TITLE_TIME
            )
            val options = arrayOf(
                getString(R.string.filename_format_original),
                getString(R.string.filename_format_timestamp),
                getString(R.string.filename_format_title_time)
            )
            val checked = values.indexOf(viewModel.uiState.value.filenameFormat).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.filename_format_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setFilenameFormat(values[which])
                    refreshFilenameFormatValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshFilenameFormatValue() {
        binding.tvFilenameFormatValue.text = when (viewModel.uiState.value.filenameFormat) {
            SettingsManager.FilenameFormat.ORIGINAL -> getString(R.string.filename_format_original)
            SettingsManager.FilenameFormat.TITLE_TIME -> getString(R.string.filename_format_title_time)
            SettingsManager.FilenameFormat.TIMESTAMP -> getString(R.string.filename_format_timestamp)
        }
    }

    /** 主题强调色（单选弹窗；选择后重建 Activity 应用新主题）。 */
    private fun setupAccentTheme() {
        refreshAccentThemeValue()
        binding.rowAccentTheme.setOnClickListener {
            val values = listOf(
                SettingsManager.AccentTheme.DEFAULT,
                SettingsManager.AccentTheme.VIOLET,
                SettingsManager.AccentTheme.OCEAN,
                SettingsManager.AccentTheme.FOREST,
                SettingsManager.AccentTheme.SUNSET,
                SettingsManager.AccentTheme.ROSE
            )
            val labels = listOf(
                R.string.accent_default,
                R.string.accent_violet,
                R.string.accent_ocean,
                R.string.accent_forest,
                R.string.accent_sunset,
                R.string.accent_rose
            )
            val options = labels.map { getString(it) }.toTypedArray()
            val checked = values.indexOf(viewModel.uiState.value.accentTheme).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.accent_theme_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    dialog.dismiss()
                    if (values[which] != viewModel.uiState.value.accentTheme) {
                        viewModel.setAccentTheme(values[which])
                        // 主题资源在 Activity 创建时注入，需重建才能生效
                        (activity as? AppNavigator)?.restartForLanguageChange()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshAccentThemeValue() {
        binding.tvAccentThemeValue.text = when (viewModel.uiState.value.accentTheme) {
            SettingsManager.AccentTheme.DEFAULT -> getString(R.string.accent_default)
            SettingsManager.AccentTheme.VIOLET -> getString(R.string.accent_violet)
            SettingsManager.AccentTheme.OCEAN -> getString(R.string.accent_ocean)
            SettingsManager.AccentTheme.FOREST -> getString(R.string.accent_forest)
            SettingsManager.AccentTheme.SUNSET -> getString(R.string.accent_sunset)
            SettingsManager.AccentTheme.ROSE -> getString(R.string.accent_rose)
        }
    }

    /** 减少动画开关。 */
    private fun setupReduceAnimations() {
        binding.switchReduceAnimations.isChecked = viewModel.uiState.value.reduceAnimationsEnabled
        binding.switchReduceAnimations.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setReduceAnimationsEnabled(isChecked)
        }
    }

    /** 列表网格列数（单选弹窗：单列/双列/三列）。 */
    private fun setupGridColumns() {
        refreshGridColumnsValue()
        binding.rowGridColumns.setOnClickListener {
            val values = intArrayOf(1, 2, 3)
            val labels = listOf(
                R.string.grid_columns_1,
                R.string.grid_columns_2,
                R.string.grid_columns_3
            )
            val options = labels.map { getString(it) }.toTypedArray()
            val checked = values.indexOf(viewModel.uiState.value.gridColumns).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.grid_columns_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setGridColumns(values[which])
                    refreshGridColumnsValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshGridColumnsValue() {
        binding.tvGridColumnsValue.text = when (viewModel.uiState.value.gridColumns) {
            2 -> getString(R.string.grid_columns_2)
            3 -> getString(R.string.grid_columns_3)
            else -> getString(R.string.grid_columns_1)
        }
    }

    /** 更新通道（单选弹窗：稳定版 / Beta）。 */
    private fun setupUpdateChannel() {
        refreshUpdateChannelValue()
        binding.rowUpdateChannel.setOnClickListener {
            val values = listOf(
                SettingsManager.UpdateChannel.STABLE,
                SettingsManager.UpdateChannel.BETA
            )
            val options = arrayOf(
                getString(R.string.update_channel_stable),
                getString(R.string.update_channel_beta)
            )
            val checked = values.indexOf(viewModel.uiState.value.updateChannel).coerceAtLeast(0)
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.update_channel_label)
                .setSingleChoiceItems(options, checked) { dialog, which ->
                    viewModel.setUpdateChannel(values[which])
                    refreshUpdateChannelValue()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshUpdateChannelValue() {
        binding.tvUpdateChannelValue.text = when (viewModel.uiState.value.updateChannel) {
            SettingsManager.UpdateChannel.BETA -> getString(R.string.update_channel_beta)
            SettingsManager.UpdateChannel.STABLE -> getString(R.string.update_channel_stable)
        }
    }

    /** 定时自动备份：开关 + 备份目录选择。开启时若未选目录则先弹目录选择器。 */
    private fun setupAutoBackup() {
        binding.switchAutoBackup.isChecked = viewModel.uiState.value.autoBackupEnabled
        binding.tvAutoBackupLocation.text = viewModel.uiState.value.autoBackupLocationText

        binding.switchAutoBackup.setOnCheckedChangeListener { _, checked ->
            if (checked && !viewModel.uiState.value.autoBackupLocationSet) {
                // 未选目录：回弹开关并先选择目录
                binding.switchAutoBackup.isChecked = false
                launchAutoBackupFolderPicker()
            } else {
                viewModel.setAutoBackupEnabled(checked)
            }
        }
        binding.rowAutoBackupLocation.setOnClickListener {
            launchAutoBackupFolderPicker()
        }
    }

    private fun launchAutoBackupFolderPicker() {
        try {
            pickAutoBackupLocation.launch(null)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.file_picker_not_available, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 清除本地全部数据：两级确认（警告 → 不可恢复再次确认），最终确认后执行并重启应用。
     */
    private fun setupWipeData() {
        binding.rowWipeData.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.wipe_data_label)
                .setMessage(R.string.wipe_data_confirm_first)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.wipe_data_continue) { _, _ ->
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.wipe_data_label)
                        .setMessage(R.string.wipe_data_confirm_second)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.wipe_data_execute) { _, _ ->
                            viewModel.wipeAllLocalData()
                            // 设置与会话已清空，重启应用使全部 UI 复位
                            (activity as? AppNavigator)?.restartForLanguageChange()
                        }
                        .show()
                }
                .show()
        }
    }
}
