package com.apex.agent.plugin.market.ingest

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.plugin.market.format.AgentPluginManifest
import com.apex.agent.plugin.market.format.PluginCapability
import com.apex.agent.plugin.market.format.PluginCompat
import com.apex.agent.plugin.market.format.PluginComponents
import com.apex.agent.plugin.market.format.ManifestSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Claude Code marketplace 摄入（Spec §9.1：镜像源策略）。
 *
 * 市场后端可定期抓取公开 marketplace 仓库（.claude-plugin 目录下的
 * marketplace.json），把 plugins 条目导入为 Android 市场候选插件。
 * 保留原始元数据（name/description/version/author/keywords），标注
 * 「来源：Claude Code Marketplace」；安装仍走 Android 端权限确认
 * （Spec §9.1 互通纪律 —— 不豁免安全链）。
 */
@Serializable
data class ClaudeMarketplaceFile(
    val name: String = "",
    val owner: JsonElement? = null,
    val plugins: List<ClaudePluginEntry> = emptyList(),
)

@Serializable
data class ClaudePluginEntry(
    val name: String = "",
    val source: String = "",
    val description: String = "",
    val version: String = "",
    val author: JsonElement? = null,
    val keywords: List<String> = emptyList(),
    val homepage: String = "",
    val license: String = "",
    /** MCP server 声明（Claude 格式：任意 JSON 对象 —— 形态各异，透传存在性）。 */
    val mcpServers: JsonElement? = null,
    /** hook 声明（Claude 格式）。 */
    val hooks: JsonElement? = null,
    /** skill 目录引用（Claude 格式：skills 数组）。 */
    val skills: JsonElement? = null,
)

/**
 * Claude 条目 → agent-plugin.json 映射器。
 *
 * 映射决策（Spec §9.1 + 保守安全原则）：
 * - **id 合成**：Claude 条目无反向域名 id → `claude-marketplace.<name>`
 *   （前缀约定隔离来源域，安装后与本仓原生插件无命名冲突）；
 * - **compat.claudeMarketplace = true**：来源标注（详情页「来源：
 *   Claude Code Marketplace」徽章数据源）；
 * - **mcpServers/hooks/skills 存在性** → components 对应字段（包内
 *   落点由市场后端打包器按约定路径生成 mcp/servers.json 等）；
 * - **能力合成**：Claude 格式**没有风险声明** —— 含 mcp/hooks 的条目
 *   合成默认 R2 capability（保守档：写操作级，安装与调用都过确认卡，
 *   绝不因为来源格式缺字段而降为自动执行）；
 * - author 形态多变（对象/字符串/null）：提取 name 字符串，其余折叠。
 */
object ClaudeMarketplaceMapper {

    private const val source = "ClaudeMarketplaceMapper"

    /** 合成 id 的来源前缀（claude-marketplace.<plugin-name>）。 */
    const val ID_PREFIX = "claude-marketplace"

    /** Claude 来源条目缺风险声明时的保守合成档位（Spec §8.1 R2 = 写操作级）。 */
    const val SYNTHESIZED_RISK = "R2"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 marketplace.json（防御式：畸形 → null）。 */
    fun parse(text: String): ClaudeMarketplaceFile? = try {
        json.decodeFromString(ClaudeMarketplaceFile.serializer(), text.trim())
    } catch (e: Exception) {
        AppLogger.instance.warn(
            LogCategory.PLUGIN, source,
            "marketplace.json 解析失败（折叠为 null）: ${e.message}"
        )
        null
    }

    /**
     * 单条目映射（name 为空或非 slug → null：无法建立安装身份的条目
     * 没有摄入价值；version 缺省合成 0.0.0 —— 详情页展示「未标版本」）。
     */
    fun mapEntry(entry: ClaudePluginEntry): AgentPluginManifest? {
        if (entry.name.isEmpty() || !ManifestSchema.NAME_PATTERN.matches(entry.name)) return null
        val hasMcp = entry.mcpServers != null
        val hasHooks = entry.hooks != null
        val hasSkills = entry.skills != null
        val capabilities = if (hasMcp || hasHooks) {
            listOf(
                PluginCapability(
                    id = "claude.import.default",
                    risk = SYNTHESIZED_RISK,
                    description = "Claude 来源插件（源格式无风险声明，按保守档 R2 审批）",
                )
            )
        } else emptyList()
        return AgentPluginManifest(
            schemaVersion = ManifestSchema.SCHEMA_VERSION,
            id = "$ID_PREFIX.${entry.name}",
            name = entry.name,
            displayName = entry.name,
            version = entry.version.ifEmpty { "0.0.0" },
            description = entry.description,
            author = com.apex.agent.plugin.market.format.PluginAuthor(
                name = authorNameOf(entry.author),
            ),
            license = entry.license,
            category = "other",
            tags = entry.keywords.take(ManifestSchemaValidatorRef.MAX_TAGS),
            // 源格式无 Android 概念 —— 合成 AppFunctions 现实下限（Spec：minAndroidApi 34+）
            minAndroidApi = 34,
            compat = PluginCompat(claudeMarketplace = true),
            components = PluginComponents(
                skills = if (hasSkills) listOf("skills") else emptyList(),
                mcpServers = if (hasMcp) "mcp/servers.json" else null,
                hooks = if (hasHooks) "hooks" else null,
            ),
            capabilities = capabilities,
        )
    }

    /**
     * 全文件映射（条目级防御：单条失败跳过，计数透传）。
     */
    fun mapFile(file: ClaudeMarketplaceFile): MappedResult {
        val mapped = mutableListOf<AgentPluginManifest>()
        var skipped = 0
        for (entry in file.plugins) {
            val m = mapEntry(entry)
            if (m == null) skipped++ else mapped += m
        }
        return MappedResult(mapped.toList(), skipped)
    }

    data class MappedResult(
        val manifests: List<AgentPluginManifest>,
        val skippedEntries: Int,
    )

    /** author 字段形态多变：对象取 name；字符串直用；其余空。 */
    private fun authorNameOf(author: JsonElement?): String = try {
        when {
            author == null -> ""
            author is kotlinx.serialization.json.JsonObject ->
                (author["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            author is kotlinx.serialization.json.JsonPrimitive -> author.content
            else -> ""
        }
    } catch (e: Exception) {
        ""
    }
}

/** 仅供 Mapper 内部引用的校验常量桥（tags 上限）。 */
internal object ManifestSchemaValidatorRef {
    const val MAX_TAGS = 12
}
