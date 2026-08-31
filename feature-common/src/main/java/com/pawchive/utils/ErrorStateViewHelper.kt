package com.pawchive.utils

import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.pawchive.common.R

/**
 * 错误状态视图绑定辅助工具（FEATURE-006）。
 *
 * 配合 [R.layout.layout_error_state] 使用，封装"展示错误文案 + 绑定重试回调"的样板代码。
 * 各页面通过 `<include layout="@layout/layout_error_state" />` 引入错误视图后，
 * 调用 [bind] 即可获得一个 [Bound] 句柄，用于控制显示/隐藏与更新文案。
 */
object ErrorStateViewHelper {

    /**
     * 绑定错误状态视图。
     * @param root 已 include 错误视图的根容器（通过 findViewById 查找子视图）
     * @param onRetry 用户点击"重试"按钮时的回调
     * @return [Bound] 句柄，调用 [Bound.show] / [Bound.hide] 控制可见性
     */
    fun bind(root: View, onRetry: () -> Unit): Bound {
        val container = root.findViewById<View>(R.id.layout_error_state)
            ?: return Bound(null, null, null)
        val messageView = root.findViewById<TextView>(R.id.tv_error_message)
        val retryButton = root.findViewById<MaterialButton>(R.id.btn_retry)
        retryButton?.setOnClickListener { onRetry() }
        return Bound(container, messageView, retryButton)
    }

    /**
     * 错误状态视图句柄。
     * - [show]：展示错误视图并设置文案
     * - [hide]：隐藏错误视图
     */
    data class Bound(
        private val container: View?,
        private val messageView: TextView?,
        private val retryButton: MaterialButton?
    ) {
        /** 展示错误视图并设置文案 */
        fun show(message: String) {
            container?.visibility = View.VISIBLE
            messageView?.text = message
        }

        /** 展示错误视图，使用默认文案 */
        fun show() {
            container?.visibility = View.VISIBLE
        }

        /** 隐藏错误视图 */
        fun hide() {
            container?.visibility = View.GONE
        }
    }
}
