package com.apex.agent.plugin.market.format

import kotlinx.serialization.Serializable

/**
 * agent-plugin.json 的完整数据模型（.agentpkg 格式 v1.0，Spec §3.1）。
 *
 * 分层纪律：
 * - **本层只管形状**：JSON 字段 → 强类型模型，全部字段带默认值，
 *   缺字段不炸（防御式 IO）；
 * - **语义交给 [ManifestValidator]**：必填性、格式、取值域在校验层判定，
 *   解析层与校验层各司其职（与热更链路 HotUpdateModels / HotPackage 同款分层）。
 *
 * 字段名与 [ManifestSchema.Field] 一一对应；schema 语义见
 * docs/plugin-market/01-package-format.md。
 */
@Serializable
data class AgentPluginManifest(
    /** schema 大版本；[ManifestSchema.SCHEMA_VERSION] 之外的值由校验层拒绝。 */
    val schemaVersion: String = "",
    /** 反向域名插件 id（如 com.example.meeting-helper）—— 装机唯一身份。 */
    val id: String = "",
    /** slug 名（如 meeting-helper）—— 与 Claude Code marketplace 的 name 字段对齐。 */
    val name: String = "",
    /** 展示名（市场详情页标题）；缺省回落 name。 */
    val displayName: String = "",
    /** 语义化版本（x.y.z）。 */
    val version: String = "",
    val description: String = "",
    val author: PluginAuthor = PluginAuthor(),
    val license: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    /** 最低 Android API（AppFunctions 生态现实下限 34 由校验层以 warning 提示）。 */
    val minAndroidApi: Int = 0,
    /** 生态兼容声明（来源标记与能力开关）。 */
    val compat: PluginCompat = PluginCompat(),
    /** 组件落点声明（skills 目录列表 / mcp servers 声明 / appfunctions 绑定 / hooks 目录）。 */
    val components: PluginComponents = PluginComponents(),
    /** 能力清单 —— 安装时权限披露与运行时审批的数据源。 */
    val capabilities: List<PluginCapability> = emptyList(),
    /** 依赖插件 id（首版仅记录，不做解析安装）。 */
    val dependencies: List<String> = emptyList(),
    /** 签名元数据（算法与 keyId；签名本体在包内 SIGNATURE 文件）。 */
    val signature: PluginSignatureMeta = PluginSignatureMeta(),
)

/** 开发者身份（Claude marketplace 的 author 字段映射目标）。 */
@Serializable
data class PluginAuthor(
    val name: String = "",
    val email: String = "",
)

/** 生态兼容声明（Spec §9：来源标记驱动详情页「来源」与运行时能力开关）。 */
@Serializable
data class PluginCompat(
    /** 条目源自 Claude Code marketplace 摄入（保留原元数据映射）。 */
    val claudeMarketplace: Boolean = false,
    /** 条目含 OpenCode hook 组件（受限 Hook Adapter 加载）。 */
    val opencodePlugin: Boolean = false,
)

/**
 * 组件落点声明 —— 包内相对路径（不以斜杠开头、不含父段引用）。
 *
 * 存在性由 [com.apex.agent.plugin.market.pkg.PluginPackageReader] 在解包时
 * 对账（声明的路径必须在包内存在），格式合法性由校验层先行把关。
 */
@Serializable
data class PluginComponents(
    /** Skill 目录列表（每个目录下应有 SKILL.md）。 */
    val skills: List<String> = emptyList(),
    /** MCP 远程 server 声明文件（servers.json）路径。 */
    val mcpServers: String? = null,
    /** AppFunctions 绑定声明文件（appfunctions.json）路径。 */
    val appFunctions: String? = null,
    /** OpenCode hook 目录路径。 */
    val hooks: String? = null,
)

/**
 * 单条能力声明 —— 「插件能做什么」的最小单元。
 *
 * risk 是 Spec §8.1 风险等级的字符串载体（R0..R3）；枚举化在执行管线层
 * （PR-D 的 RiskLevel）完成，格式层保持字符串以兼容市场后端 JSON 透传。
 */
@Serializable
data class PluginCapability(
    /** 能力 id（点分层，如 calendar.read）。 */
    val id: String = "",
    /** 风险等级（R0/R1/R2/R3）。 */
    val risk: String = "",
    /** 用户可读的能力描述（安装时权限清单与 R2+ 确认卡展示）。 */
    val description: String = "",
)

/** manifest 内签名元数据（算法 + keyId；签名本体在包内 SIGNATURE 文件）。 */
@Serializable
data class PluginSignatureMeta(
    /** 签名算法（唯一合法值 ES256）。 */
    val algorithm: String = "",
    /** 验签 key 标识（市场 keyring 索引）。 */
    val keyId: String = "",
)
