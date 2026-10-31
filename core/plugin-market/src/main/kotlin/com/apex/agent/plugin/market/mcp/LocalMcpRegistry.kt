package com.apex.agent.plugin.market.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.json.JsonElement

/**
 * 本地 MCP 注册表（Spec §5.2）：AppFunctions → MCP Tool 暴露。
 *
 * ## 域模型定位
 *
 * Android 的 AppFunctions 机制让应用把函数注册进**系统级工具注册表**，
 * Agent 以特权 MCP 客户端身份查询执行。本类是该机制在本仓的**域模型
 * 投影**（纯 JVM 可测）：`register` 模拟系统注册表的登记面，
 * [resolveBinding] 是插件绑定 → MCP ToolSpec 的解析面。
 * 真实 AndroidX AppFunctions 接线（KSP 注解处理 + 跨进程查询）在
 * 里程碑 M4 的 app 层接线 PR 落地，本层的注册/查询契约先行为。
 *
 * ## 工具命名
 *
 * 暴露给 LLM 的工具名带**提供者前缀**（com.example.calendar/createEvent
 * → calendar_provider.create_event）：多应用同名函数（两个日历 app 都
 * 有 createEvent）在命名空间上天然隔离 —— 与仓库既有 mcp__ 前缀纪律
 * 同哲学。
 */
class LocalMcpRegistry {

    /** 已注册的本地设备函数（系统注册表投影）。 */
    private val registered = LinkedHashMap<String, RegisteredFunction>()

    data class RegisteredFunction(
        val qualifiedName: String,
        val risk: String,
        val description: String,
        val inputSchema: JsonElement?,
    )

    /** 暴露给 LLM 的 MCP 工具规格（描述已清洗 —— 不可信输入纪律）。 */
    data class McpToolSpec(
        val toolName: String,
        val qualifiedName: String,
        val providerPrefix: String,
        val description: McpToolSanitizer.Sanitized,
        val risk: String,
        val inputSchema: JsonElement?,
    )

    /**
     * 注册本地函数（应用侧登记 / 测试注入）。
     * 重复 qualifiedName 后值覆盖（系统注册表同语义）。
     */
    fun register(function: RegisteredFunction) {
        registered[function.qualifiedName] = function
    }

    fun unregister(qualifiedName: String): Boolean =
        registered.remove(qualifiedName) != null

    fun all(): List<RegisteredFunction> = registered.values.toList()

    fun findByQualifiedName(qualifiedName: String): RegisteredFunction? =
        registered[qualifiedName]

    /**
     * 解析插件绑定 → MCP ToolSpec。
     *
     * - 注册表中不存在该函数 → null（插件声明了系统没登记的函数 ——
     *   上层折叠为「该能力不可用」并提示卸载或等提供方安装）；
     * - 绑定的 risk 与注册表不一致 → 以**注册表（设备侧声明）为准**
     *   并留痕（防插件把 R2 说成 R0 骗授权 —— 风险等级不允许低报）；
     * - 描述经 [McpToolSanitizer] 清洗（不可信输入纪律）。
     */
    fun resolveBinding(binding: AppFunctionBinding): McpToolSpec? {
        val fn = registered[binding.qualifiedName]
        if (fn == null) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, "LocalMcpRegistry",
                "插件绑定的本地函数未注册: ${binding.qualifiedName}"
            )
            return null
        }
        var effectiveRisk = fn.risk
        if (binding.risk != fn.risk) {
            // 风险等级冲突：取两者较高档（防低报）；等值比较不区分大小写无必要（同域枚举）
            effectiveRisk = higherRisk(fn.risk, binding.risk)
            AppLogger.instance.warn(
                LogCategory.PLUGIN, "LocalMcpRegistry",
                "绑定风险等级与注册表不一致（绑定 ${binding.risk} / 注册 ${fn.risk}）" +
                    "—— 取较高档 $effectiveRisk: ${binding.qualifiedName}"
            )
        }
        return McpToolSpec(
            toolName = binding.toolName.ifEmpty { binding.qualifiedName.substringAfter('/') },
            qualifiedName = binding.qualifiedName,
            providerPrefix = providerPrefixOf(binding.qualifiedName),
            description = McpToolSanitizer.sanitize(fn.description.ifEmpty { binding.description }),
            risk = effectiveRisk,
            inputSchema = fn.inputSchema ?: binding.inputSchema,
        )
    }

    /** 提供者前缀：com.example.calendar/createEvent → calendar。 */
    internal fun providerPrefixOf(qualifiedName: String): String {
        val pkg = qualifiedName.substringBefore('/')
        return pkg.substringAfterLast('.').ifEmpty { "local" }
    }

    /** 风险档位比较（R0 < R1 < R2 < R3）。 */
    internal fun higherRisk(a: String, b: String): String =
        if (riskRank(a) >= riskRank(b)) a else b

    private fun riskRank(r: String): Int = when (r) {
        "R0" -> 0; "R1" -> 1; "R2" -> 2; "R3" -> 3; else -> 3  // 未知按最高档（保守）
    }
}
