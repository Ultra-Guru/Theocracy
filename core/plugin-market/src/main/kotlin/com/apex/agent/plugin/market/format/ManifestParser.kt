package com.apex.agent.plugin.market.format

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.json.Json

/**
 * agent-plugin.json 防御式解析器（Spec §3.1 + 仓库防御式 IO 纪律）。
 *
 * 契约：
 * - **任何形状的输入都吞得下**：非法 JSON / 类型不匹配 → 返回 null 并
 *   AppLogger 留痕，绝不向上抛（与热更链路解析同款纪律）；
 * - **未知字段忽略**（ignoreUnknownKeys）—— 市场后端扩展字段不炸旧客户端；
 * - 模型字段全带默认值，缺字段不在这里报错 —— 必填性由
 *   [ManifestValidator] 判定（形状层与语义层分离）。
 */
object ManifestParser {

    private val source = "ManifestParser"

    private val json = Json {
        ignoreUnknownKeys = true
        // 显式拒绝宽松形态：引号内数字等投机解析会让错误 manifest
        // 「看起来解析成功」，把问题推迟到校验层报出更难懂的错误。
        isLenient = false
        // 缺字段走默认值（coerceInputDefaults 关闭 —— 显式 null 才视为 null，
        // 与 kotlinx 默认行为一致，避免双重语义）。
        coerceInputValues = false
    }

    /**
     * 解析 manifest JSON 文本；失败折叠为 null（AppLogger warn 留痕）。
     * 空白输入同样返回 null —— 「没有 manifest」与「manifest 坏了」
     * 对调用方同权重（由 reader 层统一转成 PackageReadResult.Failure）。
     */
    fun parse(text: String): AgentPluginManifest? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            json.decodeFromString(AgentPluginManifest.serializer(), trimmed)
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, source,
                "agent-plugin.json 解析失败（折叠为 null，不入目录）: ${e.message}"
            )
            null
        }
    }

    /** 序列化回 JSON（市场详情页预览 / Ingest 映射输出 / 测试对拍用）。 */
    fun render(manifest: AgentPluginManifest): String =
        json.encodeToString(AgentPluginManifest.serializer(), manifest)
}
