package com.pawchive.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pawchive.R
import com.pawchive.ui.account.AccountFragment
import com.pawchive.ui.favorites.AccountFavoritesFragment
import com.pawchive.ui.home.HomeFragment
import com.pawchive.ui.search.SearchFragment

/**
 * 主界面 ViewPager2 的适配器。
 * 使用 FragmentStateAdapter 管理主 Tab Fragment，配合 ViewPager2 实现跟手滑动切换。
 * 支持动态增删 page（登录/登出时收藏页显隐）。
 */
class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    // 按顺序存放可见的主 Tab menu item id
    private val pages = mutableListOf<Int>()

    /**
     * 更新可见的 page 列表，使用 DiffUtil 精确通知增删，避免重建已有 Fragment。
     */
    fun setPages(pageIds: List<Int>) {
        val oldList = pages.toList()
        val newList = pageIds.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == newList[newPos]
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldList[oldPos] == newList[newPos]
        })
        pages.clear()
        pages.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemId(position: Int): Long = pages[position].toLong()

    override fun containsItem(itemId: Long): Boolean = pages.contains(itemId.toInt())

    override fun createFragment(position: Int): Fragment {
        // FragmentStateAdapter 要求每次调用都返回新实例，
        // ViewPager2 内部会通过 savedState 恢复已创建 Fragment 的状态。
        return when (pages[position]) {
            R.id.navigation_home -> HomeFragment()
            R.id.navigation_search -> SearchFragment()
            R.id.navigation_bookmarks -> AccountFavoritesFragment()
            R.id.navigation_account -> AccountFragment()
            else -> HomeFragment()
        }
    }

    override fun getItemCount(): Int = pages.size

    /**
     * 根据 menu item id 获取 ViewPager2 中的 position
     */
    fun getPositionOfTab(tabId: Int): Int = pages.indexOf(tabId)

    /**
     * 根据 position 获取 menu item id
     */
    fun getTabIdAt(position: Int): Int =
        pages.getOrNull(position) ?: R.id.navigation_home
}
