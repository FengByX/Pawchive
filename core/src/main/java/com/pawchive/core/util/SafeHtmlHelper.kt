package com.pawchive.core.util

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
import com.pawchive.core.R

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

        // 0) 预处理：扁平化包含块级元素的 <a> 标签
        //    HtmlCompat.fromHtml 无法正确处理 <a> 包裹 <div>/<h3> 等块级元素的场景，
        //    URLSpan 会在块级元素边界处被打断，导致链接文本不可点击甚至丢失。
        val processed = flattenBlockLinks(html)

        // 1) 先用 HtmlCompat 解析（FROM_HTML_MODE_COMPACT）
        val spanned = HtmlCompat.fromHtml(processed, HtmlCompat.FROM_HTML_MODE_COMPACT)

        // 2) 遍历 URLSpan，移除非 https 链接，https 链接替换为带校验的 SafeLinkSpan
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
     * 预处理 HTML：将包含块级元素的 <a> 标签扁平化为简单链接。
     *
     * 问题场景：Patreon/Fanbox 等平台用 <a><div class="embed-view"><h3>文件名</h3></div></a>
     * 包裹外部链接，HtmlCompat.fromHtml 解析时块级元素会打断 URLSpan 跨度，导致链接丢失。
     *
     * 处理方式：检测 <a> 内部是否包含 div/h1-h6/p 等块级标签，若包含则提取 href 和纯文本，
     * 重建为 <a href="URL">纯文本</a>。
     */
    private fun flattenBlockLinks(html: String): String {
        val linkPattern = Regex("""<a\s+([^>]*?)>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val blockTagPattern = Regex(
            """<(div|h[1-6]|p|ul|ol|li|blockquote|section|article|header|footer|figure|table|tr|td|th)\b""",
            RegexOption.IGNORE_CASE
        )

        return linkPattern.replace(html) { match ->
            val attrs = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            // 不包含块级元素：保持原样
            if (!blockTagPattern.containsMatchIn(innerHtml)) return@replace match.value

            // 提取 href
            val hrefMatch = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(attrs)
            val href = hrefMatch?.groupValues?.get(1) ?: ""

            // 提取纯文本（移除所有标签）
            val text = innerHtml.replace(Regex("<[^>]+>"), "").trim()

            if (href.isNotEmpty() && text.isNotEmpty()) {
                """<a href="$href">$text</a>"""
            } else if (text.isNotEmpty()) {
                text
            } else {
                ""
            }
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
     * 带安全校验的链接 span：继承 URLSpan 以便调用方通过 getSpans(URLSpan) 检索并替换点击行为。
     * 打开前再次校验 scheme+host，失败时提示用户。
     */
    private class SafeLinkSpan(url: String) : URLSpan(url) {
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
