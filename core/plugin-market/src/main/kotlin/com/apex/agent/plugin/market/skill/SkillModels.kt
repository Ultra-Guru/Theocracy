package com.apex.agent.plugin.market.skill

/**
 * Skill 域模型：渐进式披露三阶段（Spec §4.1，Agent Skills 开放标准）。
 *
 * ## 披露模型
 *
 * ```
 * Discovery   frontmatter（name + description）  市场安装后 / Agent 会话启动时
 * Activation  完整 SKILL.md 正文                 用户请求匹配 description 时
 * Execution   scripts 与 references             Skill 指令执行到需要时
 * ```
 *
 * **API 形状即纪律**：Discovery 阶段的返回类型（[SkillManifest]）没有
 * body 字段 —— 调用方在类型层就拿不到正文，渐进式披露不是「约定」
 * 而是「不可能违反」。
 */

/**
 * Discovery 阶段模型：只暴露 frontmatter 元数据 + 落点路径。
 *
 * @property name skill 名（包内唯一键，frontmatter name）
 * @property description 描述（Agent 匹配用户请求的依据 —— Discovery 的全部语义）
 * @property skillDir 包内目录（如 skills/summarize）
 * @property allowedTools frontmatter 声明的可用工具白名单（空 = 不限制）
 * @property integrityDigest SKILL.md 的 SHA-256（来自包级 skills-lock.json，
 *   市场 Ingest 注入；运行时 Activation 前强制复核 —— MCP Skills
 *   extension 完整性模型。digest 不写在 frontmatter 里：digest 行会参与
 *   自身计算，自指无解）
 */
data class SkillManifest(
    val name: String,
    val description: String,
    val skillDir: String,
    val license: String? = null,
    val allowedTools: List<String> = emptyList(),
    val integrityDigest: String? = null,
) {
    companion object {
        /** SKILL.md 在 skill 目录下的固定文件名。 */
        const val SKILL_FILE = "SKILL.md"
    }
}

/** Activation 阶段模型：完整正文 + 已验证的完整性。 */
data class SkillDocument(
    val manifest: SkillManifest,
    /** SKILL.md 正文（frontmatter 之后）。 */
    val body: String,
    /** Activation 时长（虚拟钟注入的假时间戳，审计用；由 loader 透传）。 */
    val activatedAtMs: Long,
)

/** Execution 阶段：skill 目录下 scripts 与 references 的按需读取入口（见 SkillLoader）。 */

/** 披露阶段（审计与状态机用）。 */
enum class SkillLoadStage {
    /** 仅 frontmatter 已加载。 */
    DISCOVERY,
    /** 完整正文已加载（完整性已验证）。 */
    ACTIVATION,
    /** 执行资产已按需读取。 */
    EXECUTION,
}
