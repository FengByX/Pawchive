package com.pawchive.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.pawchive.BuildConfig
import com.pawchive.R
import com.pawchive.data.SettingsManager
import com.pawchive.data.github.UpdateChecker
import com.pawchive.data.repository.AuthRepository
import com.pawchive.databinding.ActivityMainBinding
import com.pawchive.ui.account.AccountFragment
import com.pawchive.ui.favorites.AccountFavoritesFragment
import com.pawchive.ui.home.HomeFragment
import com.pawchive.ui.search.SearchFragment
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository

    // 记录当前所在的主界面Tab ID
    private var currentMainTabId: Int = R.id.navigation_home

    // 缓存已创建的主界面 Fragment，避免每次切换都重建导致状态丢失与重复加载
    private val mainFragments = mutableMapOf<Int, Fragment>()

    // 左右滑动手势检测器：在主Tab之间切换
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                // 只在主Tab可见时（无二级页面）处理滑动切换
                if (supportFragmentManager.backStackEntryCount > 0) return false
                // 只处理水平方向的快速滑动，忽略纵向滑动
                if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)) return false
                if (kotlin.math.abs(velocityX) < 500) return false

                if (velocityX > 0) {
                    switchToAdjacentTab(-1) // 右滑 → 上一个Tab
                } else {
                    switchToAdjacentTab(1)  // 左滑 → 下一个Tab
                }
                return true
            }
        })
    }

    /**
     * 通过 attachBaseContext 应用保存的语言设置
     * 使用 createConfigurationContext 而非 setApplicationLocales，避免 Activity 重建导致的黑屏闪烁
     */
    override fun attachBaseContext(newBase: Context) {
        val settingsManager = SettingsManager(newBase)
        val language = settingsManager.getLanguage()
        val locale = Locale.forLanguageTag(language.code)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved settings before creating the activity
        SettingsManager.applyAppearance(this)

        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Enable high refresh rate
        setupHighRefreshRate()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        authRepository = AuthRepository(this)

        setupWindowInsets()
        setupBottomNavigationColors()

        // Set default fragment
        if (savedInstanceState == null) {
            switchMainTab(R.id.navigation_home)
        } else {
            // Activity 重建后 FragmentManager 已恢复旧 Fragment，清理并重建当前 Tab 避免叠加
            supportFragmentManager.fragments
                .filterNot { it.isDetached }
                .forEach { supportFragmentManager.beginTransaction().remove(it).commitNowAllowingStateLoss() }
            switchMainTab(R.id.navigation_home)
            binding.bottomNavigation.selectedItemId = R.id.navigation_home
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            switchMainTab(item.itemId)
            true
        }

        // 监听返回栈变化：二级页面自动隐藏底部导航，回到主页面时恢复
        supportFragmentManager.addOnBackStackChangedListener {
            val hasBackStack = supportFragmentManager.backStackEntryCount > 0
            setBottomNavigationVisibility(!hasBackStack)
        }

        updateBottomNavVisibility()

        // 检查 GitHub Release 更新（带 24 小时间隔，静默处理）
        UpdateChecker(this).checkAndShowDialog(
            lifecycleOwner = this,
            currentVersion = BuildConfig.VERSION_NAME,
            context = this
        )
    }

    override fun onResume() {
        super.onResume()
        updateBottomNavVisibility()
    }

    // 注意：移除 onDestroy 中 clearCache() 调用。
    // 此前每次退出 App 都会清空 Coil 磁盘缓存与 WebView 缓存，
    // 导致下次冷启动图片需全部重新下载、cf_clearance 需重新过盾，
    // 与"流畅体验"定位相悖。Coil 自身有 LRU 淘汰策略，无需手动清空。

    private fun setupHighRefreshRate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = display
            if (display != null) {
                val modes = display.supportedModes
                var bestMode: Display.Mode? = null
                var bestRefreshRate = 0f
                val defaultMode = display.mode
                val defaultWidth = defaultMode?.physicalWidth ?: 0
                val defaultHeight = defaultMode?.physicalHeight ?: 0

                for (mode in modes) {
                    if (mode.physicalWidth == defaultWidth && mode.physicalHeight == defaultHeight) {
                        if (mode.refreshRate > bestRefreshRate) {
                            bestRefreshRate = mode.refreshRate
                            bestMode = mode
                        }
                    }
                }

                if (bestMode != null && bestRefreshRate > (defaultMode?.refreshRate ?: 0f)) {
                    val params = window.attributes
                    params.preferredDisplayModeId = bestMode.modeId
                    window.attributes = params
                }
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.navHostFragment) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, insets.top, 0, 0)
            windowInsets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, insets.bottom)
            windowInsets
        }
    }

    private fun setupBottomNavigationColors() {
        val navView = binding.bottomNavigation
        val primaryContainer = getThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val onPrimaryContainer = getThemeColor(com.google.android.material.R.attr.colorOnPrimaryContainer)
        val onSurfaceVariant = getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        val colorBackground = getThemeColor(android.R.attr.colorBackground)

        navView.setBackgroundColor(colorBackground)
        navView.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(primaryContainer)

        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(onPrimaryContainer, onSurfaceVariant)
        val colorStateList = android.content.res.ColorStateList(states, colors)

        navView.itemIconTintList = colorStateList
        navView.itemTextColor = colorStateList
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    /**
     * 控制底部导航栏整体显隐，并同步调整 Fragment 容器约束
     * 二级页面隐藏，主页面显示
     */
    fun setBottomNavigationVisibility(visible: Boolean) {
        val bottomNav = binding.bottomNavigation
        if (bottomNav.visibility == (if (visible) View.VISIBLE else View.GONE)) return

        bottomNav.visibility = if (visible) View.VISIBLE else View.GONE

        val constraintLayout = binding.root as ConstraintLayout
        val constraintSet = ConstraintSet()
        constraintSet.clone(constraintLayout)

        if (visible) {
            constraintSet.connect(
                R.id.nav_host_fragment, ConstraintSet.BOTTOM,
                R.id.bottom_navigation, ConstraintSet.TOP
            )
        } else {
            constraintSet.connect(
                R.id.nav_host_fragment, ConstraintSet.BOTTOM,
                ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM
            )
        }
        constraintSet.applyTo(constraintLayout)
    }

    /**
     * 根据登录状态更新底部导航栏的可见项
     * 未登录时隐藏 Bookmarks 按钮
     */
    fun updateBottomNavVisibility() {
        val menu = binding.bottomNavigation.menu
        val bookmarksItem = menu.findItem(R.id.navigation_bookmarks)
        bookmarksItem?.isVisible = authRepository.isLoggedIn()
    }

    /**
     * 主界面Tab切换（复用已创建的 Fragment，避免每次重建）
     * 用于底部导航栏切换
     */
    private fun switchMainTab(tabId: Int) {
        val previousTabId = currentMainTabId
        currentMainTabId = tabId
        // 同步清空返回栈到根，避免异步 popBackStack 与新事务冲突导致 Fragment 叠加
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }

        val transaction = supportFragmentManager.beginTransaction()

        // 根据 Tab 索引方向设置过渡动画
        val visibleTabs = getVisibleTabIds()
        val prevIndex = visibleTabs.indexOf(previousTabId)
        val currIndex = visibleTabs.indexOf(tabId)
        if (prevIndex != -1 && currIndex != -1 && prevIndex != currIndex) {
            if (currIndex > prevIndex) {
                // 向右切换（索引增大）
                transaction.setCustomAnimations(
                    R.anim.slide_in_right,
                    R.anim.slide_out_left,
                    R.anim.slide_in_left,
                    R.anim.slide_out_right
                )
            } else {
                // 向左切换（索引减小）
                transaction.setCustomAnimations(
                    R.anim.slide_in_left,
                    R.anim.slide_out_right,
                    R.anim.slide_in_right,
                    R.anim.slide_out_left
                )
            }
        }

        // 先手动移除不属于主 Tab 的残留 Fragment（如 LoginFragment 等详情页）
        // 防止 popBackStackImmediate 后仍有残留导致叠加
        supportFragmentManager.fragments.forEach { frag ->
            val isMainFragment = mainFragments.values.any { it === frag }
            if (!isMainFragment && frag.isAdded) {
                transaction.remove(frag)
            }
        }

        // 隐藏其它已创建的主 Fragment
        mainFragments.forEach { (id, f) ->
            if (id != tabId && f.isAdded) {
                transaction.hide(f)
            }
        }

        val existing = mainFragments[tabId]
        if (existing != null && existing.isAdded) {
            transaction.show(existing)
            if (tabId == R.id.navigation_account) {
                (existing as? AccountFragment)?.updateUIForLoginState()
            }
        } else {
            val fragment: Fragment = when (tabId) {
                R.id.navigation_home -> HomeFragment()
                R.id.navigation_search -> SearchFragment()
                R.id.navigation_bookmarks -> AccountFavoritesFragment()
                R.id.navigation_account -> AccountFragment()
                else -> HomeFragment()
            }
            mainFragments[tabId] = fragment
            transaction.add(R.id.nav_host_fragment, fragment)
        }
        transaction.commit()
    }

    /**
     * 导航到详情页（加入返回栈）
     * 返回时会回到当前主界面Tab
     */
    fun navigateToDetail(fragment: Fragment) {
        val transaction = supportFragmentManager.beginTransaction()
        // 隐藏当前主 Fragment 而非移除，返回时可直接恢复其状态
        mainFragments[currentMainTabId]?.let { current ->
            if (current.isAdded && current.isVisible) {
                transaction.hide(current)
            }
        }
        transaction
            .add(R.id.nav_host_fragment, fragment)
            .addToBackStack(null)
            .commit()
    }

    /**
     * 兼容旧方法名（保持向后兼容）
     */
    fun loadFragment(fragment: Fragment) {
        navigateToDetail(fragment)
    }

    /**
     * 切换到主界面Tab（清除返回栈，显示底部导航）
     * 用于登录成功后跳转、外部请求跳转等场景
     */
    fun navigateToMainTab(tabId: Int) {
        switchMainTab(tabId)
        binding.bottomNavigation.selectedItemId = tabId
        refreshAccountLoginState()
    }

    /**
     * 刷新账户页的登录状态UI（登录/登出后调用）
     * 因为 hide/show 不触发 onResume，需要手动刷新
     */
    fun refreshAccountLoginState() {
        (mainFragments[R.id.navigation_account] as? AccountFragment)?.updateUIForLoginState()
    }

    /**
     * 获取当前主界面Tab ID
     */
    fun getCurrentMainTabId(): Int = currentMainTabId

    /**
     * 重启 Activity 以应用语言变更（带淡入淡出动画）
     */
    fun restartForLanguageChange() {
        val intent = this.intent
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        finish()
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    /**
     * 分发触摸事件：先交给手势检测器观察，再正常分发。
     * 不消费事件，不影响 Fragment 内部的滚动与点击。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 切换到相邻的主Tab（左右滑动触发）
     * @param direction -1 表示上一个Tab，1 表示下一个Tab
     */
    private fun switchToAdjacentTab(direction: Int) {
        val visibleTabs = getVisibleTabIds()
        val currentIndex = visibleTabs.indexOf(currentMainTabId)
        if (currentIndex == -1) return

        val targetIndex = currentIndex + direction
        if (targetIndex < 0 || targetIndex >= visibleTabs.size) return

        val targetTabId = visibleTabs[targetIndex]
        switchMainTab(targetTabId)
        binding.bottomNavigation.selectedItemId = targetTabId
    }

    /**
     * 获取当前可见的主Tab ID列表（按顺序），未登录时不含收藏页
     */
    private fun getVisibleTabIds(): List<Int> {
        val menu = binding.bottomNavigation.menu
        return listOf(
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_bookmarks,
            R.id.navigation_account
        ).filter { menu.findItem(it)?.isVisible == true }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupWindowInsets()
        updateBottomNavVisibility()
    }
}