package com.apex.agent.plugin.market.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * 不可信 tool description 清洗器（Spec §5.3 强制安全约束）。
 *
 * ## 威胁模型
 *
 * 第三方来源的 tool description（远程 MCP server 返回的工具描述 /
 * 插件声明的 AppFunction 描述）**视为不可信输入**：它会被拼进 Agent
 * 的工具目录上下文 —— 攻击者可在描述里写「忽略以上指令」类提示注入，
 * 劫持 Agent 行为。约束：
 *
 * 1. **数据不是指令**：清洗不改变文本语义（Agent 仍可读），
 *    但剥离控制字符（零宽字符 / 换行注入 / Bidi 劫持字符）与
 *    截断超长描述（上下文预算 + 隐藏指令空间）；
 * 2. **注入模式标记**：检测常见劫持话术（不删 —— 删了反而难以审计），
 *    结果带 flags，上层据此在详情页/确认卡展示「此描述含可疑指令式
 *    话术」警示，并把该工具的信任等级降档（执行管线接线）。
 */
object McpToolSanitizer {

    /** 描述长度上限（超长截断 —— 掐掉藏在长尾的指令空间）。 */
    const val MAX_DESCRIPTION_CHARS = 2000

    /** 控制字符与不可见字符（剥离；保留常规空白）。 */
    private val INVISIBLE_CHARS = Regex(
        "[" +
            "\\u0000-\\u0008" +   // C0 控制（除 \t \n \r）
            "\\u000B-\\u001F" +   // C0 其余
            "\\u007F" +           // DEL
            "\\u200B-\\u200F" +   // 零宽字符 + LRM/RLM
            "\\u202A-\\u202E" +   // Bidi 方向劫持
            "\\u2060-\\u2064" +   // 词连接 / 不可见运算符
            "\\uFEFF" +           // BOM
            "]"
    )

    /** 提示注入话术模式（命中即 flag；大小写不敏感）。 */
    private val INJECTION_PATTERNS = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)"),
        Regex("(?i)disregard\\s+(your|all|the)\\s+(previous|prior|above)?\\s*(instructions?|prompts?)"),
        Regex("(?i)system\\s*:\\s"),                       // 伪角色标记
        Regex("(?i)you\\s+are\\s+now\\s+a"),               // 角色劫持
        Regex("(?i)(reveal|泄露)\\s*(your\\s+)?(system\\s+)?prompt"),  // 提示词钓鱼
        Regex("(?i)developer\\s+mode|god\\s+mode|jailbreak"),
    )

    /** 清洗结果（clean 文本 + flags 供披露层降档）。 */
    data class Sanitized(
        val clean: String,
        val truncated: Boolean,
        val injectionFlags: List<String>,
    ) {
        val isSuspicious: Boolean get() = injectionFlags.isNotEmpty()
    }

    /**
     * 清洗不可信描述：剥离不可见字符 → 截断 → 注入模式检测。
     * 顺序有意义：剥离先于检测（零宽字符可拆分关键词绕过模式匹配）。
     */
    fun sanitize(description: String): Sanitized {
        val stripped = INVISIBLE_CHARS.replace(description, "")
        val truncated = stripped.length > MAX_DESCRIPTION_CHARS
        val bounded = if (truncated) stripped.take(MAX_DESCRIPTION_CHARS) else stripped
        val flags = INJECTION_PATTERNS
            .filter { it.containsMatchIn(bounded) }
            .map { it.pattern.substringBefore('\\') }  // flag 摘要取模式首词组
        if (flags.isNotEmpty()) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, "McpToolSanitizer",
                "tool description 含注入话术（标记不删，披露层降档）: ${flags.joinToString(", ")}"
            )
        }
        return Sanitized(clean = bounded, truncated = truncated, injectionFlags = flags)
    }
}
