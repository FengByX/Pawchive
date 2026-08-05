package com.pawchive.core.util

/**
 * 离线归档全文索引分词器（ARCH-FEATURE-001）。
 *
 * SQLite FTS4 默认 simple tokenizer 按空白/标点切分，中文/日文等 CJK 文本
 * 无空格边界会被视为整段 token，无法命中子串。本工具在应用层做 CJK bigram
 * 预处理（相邻两字成 token），避免依赖平台 ICU / FTS5 trigram（Android 版本
 * 差异大），兼容所有 Android 版本的三语（中/英/日）搜索。
 */
object OfflineArchiveIndexer {

    /** CJK 连续片段（中文、日文假名/汉字、韩文）。 */
    private val CJK_RUN = Regex(
        "[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uac00-\ud7af]+"
    )

    /** 非字母/数字/空白字符（CJK 标点、emoji 等），替换为空格保证 FTS token 边界清晰。 */
    private val NON_WORD = Regex("[^\\p{L}\\p{N}\\s\u3000]")

    /** 连续空白（含全角空格）。 */
    private val WHITESPACE = Regex("[\\s\u3000]+")

    /**
     * 把待索引文本转为 FTS 索引文本：
     * - CJK 连续片段做 bigram（相邻两字以空格连接）；
     * - 非 CJK 内容按原词保留；
     * - 非字母数字标点替换为空格，保证 FTS simple tokenizer 正确切分；
     * - 结果按空白合并，便于 FTS 按 token 匹配。
     */
    fun tokenize(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val bigrammed = CJK_RUN.replace(text) { run ->
            val s = run.value
            if (s.length <= 1) s else s.windowed(2).joinToString(" ")
        }
        val cleaned = NON_WORD.replace(bigrammed, " ")
        return WHITESPACE.split(cleaned)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    /**
     * 把用户查询转为 FTS MATCH 表达式：
     * 每个 token 加前缀通配（支持"收藏"匹配"收藏夹"），token 间用 OR 连接。
     * 使用无引号 `term*` 形式（FTS4 兼容，查询串会经 simple tokenizer 统一处理）。
     */
    fun toQuery(input: String): String {
        val q = input.trim()
        if (q.isEmpty()) return "\"\""
        val tokens = tokenize(q)?.split(" ")?.filter { it.isNotBlank() }
        if (tokens.isNullOrEmpty()) return "\"\""
        return tokens.joinToString(" OR ") { "${it.replace("\"", "")}*" }
    }

    /**
     * 单列过滤查询串（ARCH-FEATURE-001 遗留项：相关性排序权重）。
     *
     * 每个 token 独立加列前缀：`title:收藏* OR title:攻略*`。
     * 注意：不用 `title:(...)` 括号形式——sqlite4java（Robolectric 测试环境）
     * 对括号 + 前缀通配的组合不命中，而逐 token 前缀形式全环境可用。
     */
    fun toColumnQuery(input: String, column: String): String {
        val base = toQuery(input)
        if (base == "\"\"") return "\"\""
        return base.split(" OR ")
            .filter { it.isNotBlank() }
            .joinToString(" OR ") { "$column:$it" }
    }

    /**
     * 多列过滤查询串：每 token 对每列生成一个条件，OR 连接。
     * 如 `userName:收藏* OR user:收藏*`（FTS4 列过滤无括号形式）。
     */
    fun toMultiColumnQuery(input: String, vararg columns: String): String {
        val base = toQuery(input)
        if (base == "\"\"") return "\"\""
        return base.split(" OR ")
            .filter { it.isNotBlank() }
            .flatMap { tok -> columns.map { "$it:$tok" } }
            .joinToString(" OR ")
    }
}
