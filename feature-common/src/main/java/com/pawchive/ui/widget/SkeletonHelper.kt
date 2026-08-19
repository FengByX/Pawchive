package com.pawchive.ui.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.view.isVisible

/**
 * 骨架屏加载动画工具类。
 *
 * 使用方式：
 * 1. 在布局中添加一个 FrameLayout 作为骨架容器（与内容区域同级）
 * 2. 调用 [show] 显示骨架 + 启动 shimmer 动画
 * 3. 调用 [hide] 隐藏骨架 + 停止动画
 */
object SkeletonHelper {

    private const val SHIMMER_DURATION_MS = 1200L

    /**
     * 显示骨架屏并启动 shimmer 动画。
     *
     * @param skeletonView 骨架屏根 View（通常是 include 的骨架布局）
     * @param contentView  内容 View（加载完成后显示）
     */
    fun show(skeletonView: View, contentView: View) {
        skeletonView.visibility = View.VISIBLE
        contentView.visibility = View.INVISIBLE
        startShimmer(skeletonView)
    }

    /**
     * 隐藏骨架屏并显示内容，带淡入淡出过渡。
     *
     * @param skeletonView 骨架屏根 View
     * @param contentView  内容 View
     * @param animate      是否使用动画过渡
     */
    fun hide(skeletonView: View, contentView: View, animate: Boolean = true) {
        stopShimmer(skeletonView)
        if (animate) {
            skeletonView.animate()
                .alpha(0f)
                .setDuration(200)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        skeletonView.visibility = View.GONE
                        skeletonView.alpha = 1f
                    }
                })
                .start()
            contentView.alpha = 0f
            contentView.visibility = View.VISIBLE
            contentView.animate()
                .alpha(1f)
                .setDuration(200)
                .start()
        } else {
            skeletonView.visibility = View.GONE
            contentView.visibility = View.VISIBLE
        }
    }

    /**
     * 对骨架屏中的所有子 View 启动平移动画，模拟 shimmer 效果。
     */
    private fun startShimmer(root: View) {
        val shimmerViews = mutableListOf<View>()
        collectViews(root, shimmerViews)
        for ((index, view) in shimmerViews.withIndex()) {
            val animator = ValueAnimator.ofFloat(0.8f, 1f, 0.8f).apply {
                duration = SHIMMER_DURATION_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                startDelay = (index * 50L) % SHIMMER_DURATION_MS
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    view.alpha = value
                }
            }
            view.tag = animator
            animator.start()
        }
    }

    /**
     * 停止所有 shimmer 动画。
     */
    private fun stopShimmer(root: View) {
        val shimmerViews = mutableListOf<View>()
        collectViews(root, shimmerViews)
        for (view in shimmerViews) {
            val animator = view.tag as? ValueAnimator
            animator?.cancel()
            view.alpha = 1f
            view.tag = null
        }
    }

    /**
     * 递归收集所有有 background 的叶子 View。
     */
    private fun collectViews(view: View, result: MutableList<View>) {
        if (view.background != null && view !is ViewGroup) {
            result.add(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectViews(view.getChildAt(i), result)
            }
        }
    }

    // tag stored directly on view.tag
}

