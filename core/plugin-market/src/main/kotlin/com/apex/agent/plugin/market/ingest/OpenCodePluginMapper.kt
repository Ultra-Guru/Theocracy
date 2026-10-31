package com.apex.agent.plugin.market.ingest

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.plugin.market.format.AgentPluginManifest
import com.apex.agent.plugin.market.format.ManifestSchema
import com.apex.agent.plugin.market.format.PluginCompat
import com.apex.agent.plugin.market.format.PluginComponents
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * OpenCode 插件摄入（Spec §6/§9.2：移动端适配层策略）。
 *
 * OpenCode 插件是 npm 包形态，package.json 声明 opencode 入口
 * （plugin 字段指向 JS/TS 模块）。市场识别后包装为
 * compat.opencodePlugin = true 的可安装条目，运行时由受限
 * Hook Adapter 加载（[HookAdapterContract] 定义兼容范围 ——
 * JS 引擎集成是后续里程碑，本层先冻结契约）。
 */
@Serializable
data class OpenCodePackageJson(
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val license: String = "",
    val keywords: List<String> = emptyList(),
    /** opencode 入口声明：字符串（入口路径）或对象（plugin/hooks 细分）。 */
    val opencode: JsonElement? = null,
)

/**
 * OpenCode npm 包 → 市场条目映射器。
 */
object OpenCodePluginMapper {

    private const val source = "OpenCodePluginMapper"

    const val ID_PREFIX = "opencode-npm"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): OpenCodePackageJson? = try {
        json.decodeFromString(OpenCodePackageJson.serializer(), text.trim())
    } catch (e: Exception) {
        AppLogger.instance.warn(
            LogCategory.PLUGIN, source,
            "package.json 解析失败（折叠为 null）: ${e.message}"
        )
        null
    }

    /**
     * 识别 OpenCode 插件：opencode 字段存在且形态合法（非空字符串入口
     * 或含 plugin/hooks 键的对象）。普通 npm 包（无 opencode 声明）
     * 返回 null —— 不摄入（Spec §9.2：不伪装适配，明确标注兼容性）。
     */
    fun isOpenCodePlugin(pkg: OpenCodePackageJson): Boolean {
        val oc = pkg.opencode ?: return false
        return when {
            oc is JsonPrimitive && oc.content.isNotBlank() -> true
            oc is JsonObject && oc.containsKey("plugin") -> true
            else -> false
        }
    }

    /**
     * 映射为市场条目（npm 名直接当 slug —— npm 命名规则与 slug 兼容）。
     */
    fun map(pkg: OpenCodePackageJson): AgentPluginManifest? {
        if (!isOpenCodePlugin(pkg)) return null
        if (pkg.name.isEmpty() || !ManifestSchema.NAME_PATTERN.matches(pkg.name)) return null
        val oc = pkg.opencode
        val hasHooksEntry = oc is JsonObject && oc.containsKey("hooks")
        return AgentPluginManifest(
            schemaVersion = ManifestSchema.SCHEMA_VERSION,
            id = "$ID_PREFIX.${pkg.name}",
            name = pkg.name,
            displayName = pkg.name,
            version = pkg.version.ifEmpty { "0.0.0" },
            description = pkg.description,
            license = pkg.license,
            category = "development",
            tags = pkg.keywords.take(12),
            // 源格式无 Android 概念 —— 合成 AppFunctions 现实下限
            minAndroidApi = 34,
            compat = PluginCompat(opencodePlugin = true),
            components = PluginComponents(
                hooks = if (hasHooksEntry) "hooks" else null,
            ),
            capabilities = if (hasHooksEntry) listOf(
                com.apex.agent.plugin.market.format.PluginCapability(
                    id = "opencode.hooks",
                    risk = "R2",
                    description = "OpenCode hook 脚本（受限沙箱执行，写操作级审批）",
                )
            ) else emptyList(),
        )
    }

    /** 便捷：解析 + 识别 + 映射一步（非 OpenCode 包 / 坏 JSON → null）。 */
    fun parseAndMap(text: String): AgentPluginManifest? {
        val pkg = parse(text) ?: return null
        return map(pkg)
    }
}
