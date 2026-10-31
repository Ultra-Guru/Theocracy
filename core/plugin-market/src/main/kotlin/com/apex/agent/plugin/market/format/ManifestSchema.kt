package com.apex.agent.plugin.market.format

/**
 * 插件市场 manifest 的 schema 常量表（.agentpkg 格式 v1.0，Spec §3.1）。
 *
 * 这个对象是 agent-plugin.json 字段名与取值域的**唯一真相源**：
 * - 解析层（[ManifestParser]）按这里的字段名建 JSON 映射；
 * - 校验层（[ManifestValidator]）按这里的取值域判合法；
 * - 文档（docs/plugin-market/01-package-format.md）与字段表一一对应，
 *   plugin-ci 的「文档一致性检查」以此对齐（PR-E 接线）。
 *
 * 纪律：字段表变更必须同步 docs/plugin-market/01-package-format.md ——
 * 市场后端（Ingest Svc）与插件开发者都以文档为准摄入/产出。
 */
object ManifestSchema {

    /** 当前支持的 manifest schema 大版本（v1.0）。 */
    const val SCHEMA_VERSION = "1.0"

    /** manifest 在 .agentpkg 包内的固定路径。 */
    const val MANIFEST_PATH = "agent-plugin.json"

    /** 包内签名文件（ES256）的固定路径。 */
    const val SIGNATURE_PATH = "SIGNATURE"

    /** 风险等级合法取值（Spec §8.1：R0 只读 / R1 读用户数据 / R2 写状态 / R3 敏感）。 */
    val RISK_LEVELS = setOf("R0", "R1", "R2", "R3")

    /** 签名算法唯一取值 —— ES256（ECDSA P-256 + SHA-256，JOSE raw r||s 编码）。 */
    const val SIGNATURE_ALGORITHM = "ES256"

    /** manifest 各字段的 JSON 键名（校验报错时的路径引用与文档表格同源）。 */
    object Field {
        const val SCHEMA_VERSION = "schemaVersion"
        const val ID = "id"
        const val NAME = "name"
        const val DISPLAY_NAME = "displayName"
        const val VERSION = "version"
        const val DESCRIPTION = "description"
        const val AUTHOR = "author"
        const val AUTHOR_NAME = "name"
        const val AUTHOR_EMAIL = "email"
        const val LICENSE = "license"
        const val CATEGORY = "category"
        const val TAGS = "tags"
        const val MIN_ANDROID_API = "minAndroidApi"
        const val COMPAT = "compat"
        const val COMPAT_CLAUDE_MARKETPLACE = "claudeMarketplace"
        const val COMPAT_OPENCODE_PLUGIN = "opencodePlugin"
        const val COMPONENTS = "components"
        const val COMPONENT_SKILLS = "skills"
        const val COMPONENT_MCP_SERVERS = "mcpServers"
        const val COMPONENT_APP_FUNCTIONS = "appFunctions"
        const val COMPONENT_HOOKS = "hooks"
        const val CAPABILITIES = "capabilities"
        const val CAPABILITY_ID = "id"
        const val CAPABILITY_RISK = "risk"
        const val CAPABILITY_DESCRIPTION = "description"
        const val DEPENDENCIES = "dependencies"
        const val SIGNATURE = "signature"
        const val SIGNATURE_ALGORITHM = "algorithm"
        const val SIGNATURE_KEY_ID = "keyId"
    }

    /**
     * 市场类目白名单（详情页分类与搜索索引用；类目外的值是 warning 而非
     * error —— 类目会随市场运营扩展，格式层不做硬门槛）。
     */
    val CATEGORIES = setOf(
        "productivity", "development", "automation", "data",
        "communication", "media", "system", "education", "entertainment", "other",
    )

    /** id 的反向域名格式（至少两段；域名标签允许连字符 —— RFC 1035，与 Claude 摄入前缀 claude-marketplace 兼容）。 */
    val ID_PATTERN = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+){1,}$")

    /** name 的 slug 格式（小写字母数字 + 连字符，与 npm/Claude 插件名约定一致）。 */
    val NAME_PATTERN = Regex("^[a-z0-9][a-z0-9-]*$")

    /** 语义化版本（x.y.z 可选先行段；市场目录按此排序）。 */
    val SEMVER_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$")
}
