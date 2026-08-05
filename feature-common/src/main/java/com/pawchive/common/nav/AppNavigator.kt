package com.pawchive.common.nav

import androidx.fragment.app.Fragment

/**
 * 跨 Feature 模块导航接口（ARCH-002 阶段 3）。
 *
 * 由 `:app` 的 MainActivity 实现；feature 模块通过 `activity as? AppNavigator`
 * 获取实例并调用，不再直接 new 其他 feature 的 Fragment（消除 feature 间直接依赖）。
 */
interface AppNavigator {

    /** 打开帖子详情页（服务 + 创作者 id + 帖子 id） */
    fun openPostDetail(service: String, creatorId: String, postId: String)

    /** 打开创作者主页（服务 + 创作者 id） */
    fun openCreatorProfile(service: String, creatorId: String)

    /** 打开登录页 */
    fun openLogin()

    /** 打开设置页 */
    fun openSettings()

    /** 打开同模块内的通用 Fragment（如全屏图片查看器），加入返回栈 */
    fun openFragment(fragment: Fragment)

    /** 切换到首页主 Tab（清除返回栈） */
    fun navigateToHomeTab()

    /** 切换到收藏主 Tab（清除返回栈，登录成功后跳转） */
    fun navigateToBookmarksTab()

    /** 重启 Activity 以应用语言变更 */
    fun restartForLanguageChange()

    /** 刷新底部导航可见性（登录/登出后） */
    fun updateBottomNavVisibility()
}
