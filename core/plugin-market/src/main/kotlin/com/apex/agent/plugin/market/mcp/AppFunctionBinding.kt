package com.apex.agent.plugin.market.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * AppFunctions 绑定声明（Spec §5.2，appfunctions.json）。
 *
 * 插件声明它要调用哪些**本地设备函数**（如 com.example.calendar/createEvent）；
 * 市场详情页据此展示「此插件将调用以下设备函数」（每个附风险等级与
 * 用户可读描述）。运行时由 [LocalMcpRegistry] 把绑定解析为 MCP Tool。
 *
 * inputSchema 是 JSON Schema 形状的参数描述（透传 [JsonElement]，
 * 不在本层校验 schema 本身的合法性 —— 那是执行管线 Schema 校验段的事）。
 */
@Serializable
data class AppFunctionBinding(
    /** 限定函数名（包名/函数名，如 com.example.calendar/createEvent）。 */
    val qualifiedName: String = "",
    /** 风险等级（R0..R3，与 manifest.capabilities 同域）。 */
    val risk: String = "",
    /** 用户可读描述（安装时权限清单与 R2+ 确认卡展示）。 */
    val description: String = "",
    /** 参数 JSON Schema（可选 —— 无参函数缺省）。 */
    val inputSchema: JsonElement? = null,
    /** 绑定暴露给 Agent 的工具短名（缺省取 qualifiedName 的函数段）。 */
    val toolName: String = "",
)

/** appfunctions.json 根模型（schema: apex-appfunctions-v1）。 */
@Serializable
data class AppFunctionsFile(
    val schema: String = "",
    val functions: List<AppFunctionBinding> = emptyList(),
)

/**
 * appfunctions.json 解析与校验。
 *
 * 校验规则（条目级剔除，防御式）：
 * - qualifiedName 必须形如「包名/函数名」（至少一段斜杠，两段非空）；
 * - risk ∈ R0..R3（[com.apex.agent.plugin.market.format.ManifestSchema.RISK_LEVELS]）；
 * - toolName 缺省取函数段；显式给定时必须是合法工具名（小写字母数字下划线）。
 */
object AppFunctionBindingCodec {

    private const val source = "AppFunctionBindingCodec"

    const val SCHEMA = "apex-appfunctions-v1"

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    private val QUALIFIED_NAME = Regex("^[a-z][a-z0-9_.]*/[a-zA-Z][a-zA-Z0-9_-]*$")
    private val TOOL_NAME = Regex("^[a-z][a-z0-9_]*$")

    fun parse(text: String): AppFunctionsFile? = try {
        json.decodeFromString(AppFunctionsFile.serializer(), text.trim())
    } catch (e: Exception) {
        AppLogger.instance.warn(
            LogCategory.PLUGIN, source,
            "appfunctions.json 解析失败（折叠为 null）: ${e.message}"
        )
        null
    }

    fun sanitize(file: AppFunctionsFile): SanitizedBindings {
        val valid = mutableListOf<AppFunctionBinding>()
        val rejections = mutableListOf<String>()
        val seenQualified = HashSet<String>()
        for (f in file.functions) {
            val problems = mutableListOf<String>()
            if (!QUALIFIED_NAME.matches(f.qualifiedName)) problems.add("qualifiedName 非法（\"${f.qualifiedName}\"）")
            if (!seenQualified.add(f.qualifiedName)) problems.add("qualifiedName 重复")
            if (f.risk !in com.apex.agent.plugin.market.format.ManifestSchema.RISK_LEVELS) {
                problems.add("risk 非法（\"${f.risk}\"）")
            }
            if (f.toolName.isNotEmpty() && !TOOL_NAME.matches(f.toolName)) {
                problems.add("toolName 非法（\"${f.toolName}\"）")
            }
            if (problems.isEmpty()) valid += f.withDefaultToolName()
            else {
                rejections.add("function \"${f.qualifiedName}\": ${problems.joinToString("; ")}")
                AppLogger.instance.warn(
                    LogCategory.PLUGIN, source,
                    "AppFunction 绑定被剔除: ${problems.joinToString("; ")}"
                )
            }
        }
        return SanitizedBindings(valid.toList(), rejections.toList())
    }

    data class SanitizedBindings(
        val functions: List<AppFunctionBinding>,
        val rejections: List<String>,
    )

    /** toolName 缺省 = qualifiedName 的函数段（com.example.calendar/createEvent → createEvent）。 */
    private fun AppFunctionBinding.withDefaultToolName(): AppFunctionBinding =
        if (toolName.isEmpty()) copy(toolName = qualifiedName.substringAfter('/')) else this
}
