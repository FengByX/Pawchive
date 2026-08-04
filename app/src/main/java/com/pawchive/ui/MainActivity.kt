package com.pawchive.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.pawchive.BuildConfig
import com.pawchive.R
import com.pawchive.data.SettingsManager
import com.pawchive.data.github.UpdateChecker
import com.pawchive.data.repository.AuthRepository
import com.pawchive.databinding.ActivityMainBinding
import com.pawchive.ui.account.AccountFragment
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var authRepository: AuthRepository
    private lateinit var pagerAdapter: MainPagerAdapter

    override fun attachBaseContext(newBase: Context) {
        val settingsManager = SettingsManager.getInstance(newBase)
        val language = settingsManager.getLanguage()
        val locale = Locale.forLanguageTag(language.code)
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsManager.applyAppearance(this)
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupHighRefreshRate()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        authRepository = AuthRepository(this)

        setupWindowInsets()
        setupBottomNavigationColors()

        // 初始化 ViewPager2 + Adapter
        pagerAdapter = MainPagerAdapter(this)
        binding.viewPager.adapter = pagerAdapter
        // 保留所有 page，避免远距离切换时重建 Fragment
        binding.viewPager.offscreenPageLimit = 3

        // 更新可见 page 列表
        updateVisiblePages()

        // ViewPager2 → BottomNavigationView 联动
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val tabId = pagerAdapter.getTabIdAt(position)
                if (binding.bottomNavigation.selectedItemId != tabId) {
                    binding.bottomNavigation.selectedItemId = tabId
                }
            }
        })

        // BottomNavigationView → ViewPager2 联动
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val position = pagerAdapter.getPositionOfTab(item.itemId)
            if (position != -1 && binding.viewPager.currentItem != position) {
                binding.viewPager.setCurrentItem(position, true)
            }
            true
        }

        // 监听返回栈变化：二级页面自动隐藏底部导航
        supportFragmentManager.addOnBackStackChangedListener {
            val hasBackStack = supportFragmentManager.backStackEntryCount > 0
            setBottomNavigationVisibility(!hasBackStack)
        }

        updateBottomNavVisibility()

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
     * 控制底部导航栏整体显隐，并同步调整 ViewPager2 容器约束。
     * 二级页面隐藏，主页面显示。
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
     * 根据登录状态更新底部导航栏的可见项，并同步 ViewPager2 的 page 列表。
     * 未登录时隐藏 Bookmarks 页。
     */
    fun updateBottomNavVisibility() {
        val loggedIn = authRepository.isLoggedIn()
        val menu = binding.bottomNavigation.menu
        menu.findItem(R.id.navigation_bookmarks)?.isVisible = loggedIn

        // 同步 ViewPager2 的 page 列表
        updateVisiblePages()
    }

    /**
     * 根据 BottomNavigationView 可见项更新 ViewPager2 的 page 列表。
     * 使用 DiffUtil 精确增删，避免已有 page 重建。
     */
    private fun updateVisiblePages() {
        val menu = binding.bottomNavigation.menu
        val visiblePageIds = listOf(
            R.id.navigation_home,
            R.id.navigation_search,
            R.id.navigation_bookmarks,
            R.id.navigation_account,
            R.id.navigation_downloads
        ).filter { menu.findItem(it)?.isVisible == true }

        val currentPosition = binding.viewPager.currentItem
        val currentTabId = if (pagerAdapter.itemCount > 0) {
            pagerAdapter.getTabIdAt(currentPosition)
        } else {
            R.id.navigation_home
        }

        pagerAdapter.setPages(visiblePageIds)

        // 保持当前 Tab 不变
        val newPosition = pagerAdapter.getPositionOfTab(currentTabId)
        if (newPosition != -1 && newPosition != currentPosition) {
            binding.viewPager.setCurrentItem(newPosition, false)
        } else if (newPosition != -1) {
            // 同步底部导航选中状态
            if (binding.bottomNavigation.selectedItemId != currentTabId) {
                binding.bottomNavigation.selectedItemId = currentTabId
            }
        }
    }

    /**
     * 导航到详情页（加入返回栈）。
     * 二级 Fragment 覆盖在 ViewPager2 之上，返回时自动恢复主页面。
     */
    fun navigateToDetail(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
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
     * 切换到主界面Tab（清除返回栈，显示底部导航）。
     * 用于登录成功后跳转、外部请求跳转等场景。
     */
    fun navigateToMainTab(tabId: Int) {
        // 清空返回栈，移除二级页面
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate(
                null,
                androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE
            )
        }
        // 切换 ViewPager2 到目标 Tab
        val position = pagerAdapter.getPositionOfTab(tabId)
        if (position != -1) {
            binding.viewPager.setCurrentItem(position, false)
        }
        if (binding.bottomNavigation.selectedItemId != tabId) {
            binding.bottomNavigation.selectedItemId = tabId
        }
    }

    /**
     * 刷新账户页的登录状态UI（登录/登出后调用）。
     * ViewPager2 切换 page 时会触发 AccountFragment.onResume，
     * 但登录后可能需要在当前 page 直接刷新，这里通过 ViewPager2 触发。
     */
    fun refreshAccountLoginState() {
        // 切换到 account page 触发其 onResume
        val position = pagerAdapter.getPositionOfTab(R.id.navigation_account)
        if (position != -1) {
            // 如果已经是当前 page，直接通知 Fragment 刷新
            val fragment = supportFragmentManager.fragments.find { it is AccountFragment }
            (fragment as? AccountFragment)?.updateUIForLoginState()
        }
    }

    /**
     * 获取当前主界面Tab ID
     */
    fun getCurrentMainTabId(): Int {
        return if (pagerAdapter.itemCount > 0) {
            pagerAdapter.getTabIdAt(binding.viewPager.currentItem)
        } else {
            R.id.navigation_home
        }
    }

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupWindowInsets()
        updateBottomNavVisibility()
    }
}
