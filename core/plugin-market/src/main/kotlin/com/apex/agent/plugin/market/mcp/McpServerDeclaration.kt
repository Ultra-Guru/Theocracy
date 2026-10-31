package com.apex.agent.plugin.market.mcp

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.plugin.market.format.ManifestSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MCP 远程 server 声明（Spec §5.1，servers.json）。
 *
 * ## 信任语义
 *
 * 声明是**插件自报的家门**：url 域名进安装时权限披露清单（用户批的
 * 是「这个插件要连这些域」），不是免检通行证 —— 连接层的 OAuth/API Key
 * 授权、TLS 校验属于传输实现（上层接线），本层只做声明模型 + 硬门槛：
 * **非 https 一律拒绝**（Spec §5 安全约束：域名白名单的前提是加密通道）。
 */
@Serializable
data class McpServerDeclaration(
    /** server 实例 id（包内唯一，如 weather）。 */
    val id: String = "",
    /** 传输形态（https / sse；SSE 也是 https 通道）。 */
    val transport: String = "",
    /** 端点 URL（必须是 https）。 */
    val url: String = "",
    /** 认证声明（安装时披露「需要授权」的依据）。 */
    val auth: McpAuthDeclaration = McpAuthDeclaration(),
    /** 用户可读用途描述（安装时权限清单展示）。 */
    val description: String = "",
)

@Serializable
data class McpAuthDeclaration(
    /** oauth / apikey（none 缺省 = 免认证公共端点）。 */
    val type: String = "",
    /** 授权提示（如「需要 GitHub OAuth」）。 */
    val hint: String = "",
)

/** servers.json 根模型（schema: apex-mcp-servers-v1）。 */
@Serializable
data class McpServersFile(
    val schema: String = "",
    val servers: List<McpServerDeclaration> = emptyList(),
)

/**
 * servers.json 解析与校验（防御式：畸形折叠 null / 违规条目剔除留痕）。
 */
object McpServerDeclarationCodec {

    private const val source = "McpServerDeclarationCodec"

    const val SCHEMA = "apex-mcp-servers-v1"
    const val TRANSPORT_HTTPS = "https"
    const val TRANSPORT_SSE = "sse"
    val TRANSPORTS = setOf(TRANSPORT_HTTPS, TRANSPORT_SSE)

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /** 解析（畸形 → null + 留痕）。 */
    fun parse(text: String): McpServersFile? = try {
        json.decodeFromString(McpServersFile.serializer(), text.trim())
    } catch (e: Exception) {
        AppLogger.instance.warn(
            LogCategory.PLUGIN, source,
            "servers.json 解析失败（折叠为 null）: ${e.message}"
        )
        null
    }

    /**
     * 条目级校验（返回合法集 + 剔除原因）：
     * - id 非空且包内唯一；
     * - transport ∈ {https, sse}；
     * - url 必须 https 起头（硬性安全门槛，Spec §5）；
     * - auth.type ∈ {none, oauth, apikey}。
     */
    fun sanitize(file: McpServersFile): SanitizedServers {
        val valid = mutableListOf<McpServerDeclaration>()
        val rejections = mutableListOf<String>()
        val seenIds = HashSet<String>()
        for (s in file.servers) {
            val problems = mutableListOf<String>()
            if (s.id.isEmpty()) problems.add("id 为空")
            if (!seenIds.add(s.id)) problems.add("id 重复")
            if (s.transport !in TRANSPORTS) problems.add("transport 非法（\"${s.transport}\"）")
            if (!s.url.startsWith("https://")) problems.add("url 非 https（\"${s.url}\"）")
            if (s.auth.type.isNotEmpty() && s.auth.type !in AUTH_TYPES) {
                problems.add("auth.type 非法（\"${s.auth.type}\"）")
            }
            if (problems.isEmpty()) valid += s
            else {
                rejections.add("server \"${s.id}\": ${problems.joinToString("; ")}")
                AppLogger.instance.warn(
                    LogCategory.PLUGIN, source,
                    "MCP server 声明被剔除: ${problems.joinToString("; ")}"
                )
            }
        }
        return SanitizedServers(valid.toList(), rejections.toList())
    }

    val AUTH_TYPES = setOf("oauth", "apikey")

    data class SanitizedServers(
        val servers: List<McpServerDeclaration>,
        val rejections: List<String>,
    )
}
