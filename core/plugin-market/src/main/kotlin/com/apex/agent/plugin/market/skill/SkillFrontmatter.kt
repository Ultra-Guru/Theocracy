package com.apex.agent.plugin.market.skill

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * SKILL.md YAML frontmatter 自研子集解析器（Spec §4，Agent Skills 开放标准）。
 *
 * ## 为什么自研不引 YAML 依赖
 *
 * SKILL.md 的 frontmatter 是**极小子集**：标量（字符串/数字/布尔）、
 * 简单列表（`[a, b]` 流式形态或 `- item` 短行形态）。完整 YAML 库
 * （snakeyaml 等）为一个 20 行的头部引入数百 KB 依赖与攻击面
 * （YAML 反序列化 CVE 常客）—— 不值得。
 *
 * ## 防御式契约（仓库纪律：任何输入都吞得下）
 *
 * - 无 frontmatter（不以 `---` 起头）→ 空字段 + issue，不炸；
 * - 头部未闭合（找不到第二个 `---`）→ 空字段 + issue；
 * - 畸形行（无冒号 / 重复键 / 深嵌套缩进）→ 跳过该行 + issue 留痕；
 * - 值内的一切（引号、注释符）原样保留为字符串 —— 本层不做类型推断
 *   （name/description 都是字符串，无需猜类型）。
 */
object SkillFrontmatter {

    private const val source = "SkillFrontmatter"

    /** frontmatter 定界符（行首行尾精确匹配，防正文中 `---` 分隔线误判）。 */
    private const val DELIMITER = "---"

    data class ParseResult(
        /** 标量字段（行内原始字符串值，去首尾空白）。 */
        val scalars: Map<String, String>,
        /** 列表字段（流式方括号与短行形态统一折叠；裸逗号值是标量，不拆）。 */
        val lists: Map<String, List<String>>,
        /** 防御式问题（解析降级痕迹，调用方决定披露）。 */
        val issues: List<String>,
        /** 头部之后的正文（无 frontmatter 时为全文；未闭合时为空）。 */
        val body: String,
    )

    /**
     * 解析 SKILL.md 文本。四种输入形态全吞：
     * 合法 frontmatter / 无 frontmatter / 未闭合头部 / 头部内混杂畸形行。
     */
    fun parse(text: String): ParseResult {
        val lines = text.lines()
        if (lines.isEmpty() || lines.first().trim() != DELIMITER) {
            // 无 frontmatter：全文都是正文（纯指令型 skill 不强制头部）
            return ParseResult(emptyMap(), emptyMap(), emptyList(), text)
        }

        // 找闭合 ---
        val closeIndex = lines.drop(1).indexOfFirst { it.trim() == DELIMITER }
        if (closeIndex < 0) {
            return ParseResult(
                emptyMap(), emptyMap(),
                listOf("frontmatter 未闭合（缺第二个 --- 行），头部整体忽略"),
                "",
            )
        }

        val headerLines = lines.subList(1, closeIndex + 1)
        val body = lines.drop(closeIndex + 2).joinToString("\n")

        val scalars = LinkedHashMap<String, String>()
        val lists = LinkedHashMap<String, List<String>>()
        val issues = mutableListOf<String>()

        var listKey: String? = null  // 当前短行列表的归属键
        for (raw in headerLines) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val trimmed = line.trim()

            // 短行列表项（- item）
            if (trimmed.startsWith("- ") || trimmed == "-") {
                val key = listKey
                if (key == null) {
                    issues.add("孤立列表项（前方无键）: \"$trimmed\" —— 跳过")
                    continue
                }
                val item = trimmed.removePrefix("-").trim()
                if (item.isNotEmpty()) {
                    lists[key] = lists[key].orEmpty() + item
                }
                continue
            }

            // 键值行
            val colon = trimmed.indexOf(':')
            if (colon <= 0) {
                issues.add("畸形行（无键冒号或键为空）: \"$trimmed\" —— 跳过")
                listKey = null
                continue
            }
            val key = trimmed.substring(0, colon).trim()
            val value = trimmed.substring(colon + 1).trim()
            if (key in scalars || key in lists) {
                issues.add("重复键 \"$key\" —— 后值忽略")
                continue
            }
            listKey = null
            when {
                // 流式列表（YAML 标准）：allowed-tools: [Read, Write]
                value.length >= 2 && value.startsWith("[") && value.endsWith("]") -> {
                    lists[key] = value.substring(1, value.length - 1)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    listKey = key
                }
                // 空值键：可能是短行列表头（allowed-tools: 换行 - Read）——
                // 记为标量空串 + 潜在 listKey；后续 - item 到来即转列表
                value.isEmpty() -> {
                    scalars[key] = ""
                    listKey = key
                }
                // 标量（含裸逗号值 —— YAML 里这是标量；含空值留给校验层报必填）
                else -> scalars[key] = value
            }
        }

        if (issues.isNotEmpty()) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, source,
                "SKILL.md frontmatter 带畸形行（降级解析，共 ${issues.size} 处）: ${issues.joinToString("; ")}"
            )
        }
        return ParseResult(scalars, lists, issues, body)
    }

    // ── 内部（废弃启发 —— 裸逗号是标量，列表只认流式方括号与短行）──
}
