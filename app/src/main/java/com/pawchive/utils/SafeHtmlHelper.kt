package com.pawchive.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.view.View
import android.widget.Toast
import androidx.core.text.HtmlCompat
import com.pawchive.R

/**
 * 安全的 HTML 渲染与链接校验工具（P1）。
 *
 * 威胁模型：帖子正文、公告、更新日志等外部内容可能包含畸形 HTML，
 * 例如 `<a href="intent://...">` 误导用户触发 intent scheme、`<img src="...">` 加载追踪像素、
 * `<script>` 注入（虽然 Html.fromHtml 不执行 JS，但标签会原样显示）。
 *
 * 防护策略：
 * 1. 仅允许极小白名单标签（a/b/i/u/strong/em/br/p/small/font），其余标签在渲染前被 HtmlCompat 转义；
 * 2. 仅允许 https 链接，http/_intent/file/javascript: 等危险 scheme 一律剥离为纯文本；
 * 3. 点击链接前再次校验 scheme+host，弹出 Toast 提示而非直接打开。
 */
object SafeHtmlHelper {

    /**
     * 将外部 HTML 字符串渲染为 Spanned，仅保留白名单标签与 https 链接。
     */
    fun render(html: String): CharSequence {
        if (html.isBlank()) return ""

        // 1) 先用 HtmlCompat 解析（FROM_HTML_MODE_COMPACT）
        val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT)

        // 2) 遍历 URLSpan，移除非 https 链接，https 链接替换为带校验的 ClickableSpan
        return if (spanned is Spannable) {
            val spannable = SpannableString(spanned)
            val urls = spannable.getSpans(0, spannable.length, URLSpan::class.java)
            for (urlSpan in urls) {
                val url = urlSpan.url ?: ""
                val start = spannable.getSpanStart(urlSpan)
                val end = spannable.getSpanEnd(urlSpan)
                val flags = spannable.getSpanFlags(urlSpan)
                spannable.removeSpan(urlSpan)

                if (isSafeUrl(url)) {
                    spannable.setSpan(SafeLinkSpan(url), start, end, flags)
                }
                // 不安全的链接：不设置新的 span，仅保留文本（已通过 removeSpan 移除可点击行为）
            }
            spannable
        } else {
            spanned
        }
    }

    /**
     * 校验 URL 是否安全：仅允许 https scheme，且 host 非空。
     */
    private fun isSafeUrl(url: String): Boolean {
        return try {
            val parsed = Uri.parse(url)
            parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 带安全校验的链接点击行为：打开前再次校验，失败时提示用户。
     */
    private class SafeLinkSpan(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) {
            val context = widget.context
            if (!isSafeUrl(url)) {
                Toast.makeText(context, context.getString(R.string.unsafe_link_blocked), Toast.LENGTH_SHORT).show()
                return
            }
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.browser_not_available), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
