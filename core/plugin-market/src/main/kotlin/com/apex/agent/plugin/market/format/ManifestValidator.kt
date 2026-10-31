package com.apex.agent.plugin.market.format

/**
 * manifest 语义校验器（Spec §3.1 必填/格式约束 + §8.1 风险取值域）。
 *
 * 输出模型是**问题清单**而非布尔 —— 市场上架审核与 IDE 插件开发工作流
 * 都需要「一次性看到全部问题」，布尔返回值会把开发者拖进逐条修复循环。
 *
 * 严重度语义：
 * - [Severity.ERROR]：拒绝上架/安装（格式不合法、路径穿越、风险等级越界）；
 * - [Severity.WARNING]：可上架但详情页标注（类目外、缺展示名、
 *   minAndroidApi 低于 AppFunctions 现实下限）。
 */
object ManifestValidator {

    /** 校验问题（code 稳定，供市场后台聚合统计；path 指向 manifest 字段）。 */
    data class Issue(
        val code: String,
        val severity: Severity,
        val path: String,
        val message: String,
    )

    enum class Severity { ERROR, WARNING }

    /** 校验结果：errors 非空即不合法；warnings 供披露层展示。 */
    data class Result(
        val errors: List<Issue>,
        val warnings: List<Issue>,
    ) {
        val isValid: Boolean get() = errors.isEmpty()
    }

    /**
     * 全量校验（一次跑完所有规则，收集全部问题，不短路）。
     *
     * 包内路径存在性不在此层 —— 那需要解包后的 entries，
     * 由 PluginPackageReader 对账（[validateComponentPresence]）。
     */
    fun validate(m: AgentPluginManifest): Result {
        val issues = mutableListOf<Issue>()

        // ── 身份与版本 ────────────────────────────────────────────────
        if (m.schemaVersion != ManifestSchema.SCHEMA_VERSION) {
            issues += Issue(
                "SCHEMA_VERSION", Severity.ERROR, ManifestSchema.Field.SCHEMA_VERSION,
                "schemaVersion 必须为 ${ManifestSchema.SCHEMA_VERSION}（当前: \"${m.schemaVersion}\"）"
            )
        }
        if (m.id.isEmpty()) {
            issues += Issue("ID_EMPTY", Severity.ERROR, ManifestSchema.Field.ID, "id 不能为空")
        } else if (!ManifestSchema.ID_PATTERN.matches(m.id)) {
            issues += Issue(
                "ID_FORMAT", Severity.ERROR, ManifestSchema.Field.ID,
                "id 须为反向域名（如 com.example.meeting-helper），当前: \"${m.id}\""
            )
        }
        if (m.name.isEmpty()) {
            issues += Issue("NAME_EMPTY", Severity.ERROR, ManifestSchema.Field.NAME, "name 不能为空")
        } else if (!ManifestSchema.NAME_PATTERN.matches(m.name)) {
            issues += Issue(
                "NAME_FORMAT", Severity.ERROR, ManifestSchema.Field.NAME,
                "name 须为 slug（小写字母数字 + 连字符），当前: \"${m.name}\""
            )
        }
        if (m.version.isEmpty()) {
            issues += Issue("VERSION_EMPTY", Severity.ERROR, ManifestSchema.Field.VERSION, "version 不能为空")
        } else if (!ManifestSchema.SEMVER_PATTERN.matches(m.version)) {
            issues += Issue(
                "VERSION_FORMAT", Severity.ERROR, ManifestSchema.Field.VERSION,
                "version 须为语义化版本（x.y.z），当前: \"${m.version}\""
            )
        }
        if (m.displayName.isEmpty()) {
            issues += Issue(
                "DISPLAY_NAME_MISSING", Severity.WARNING, ManifestSchema.Field.DISPLAY_NAME,
                "displayName 缺省 —— 详情页将回落展示 name"
            )
        }

        // ── 平台门槛 ─────────────────────────────────────────────────
        if (m.minAndroidApi < MIN_API_FLOOR) {
            issues += Issue(
                "MIN_API_BELOW_FLOOR", Severity.ERROR, ManifestSchema.Field.MIN_ANDROID_API,
                "minAndroidApi 不能低于 $MIN_API_FLOOR（当前: ${m.minAndroidApi}）"
            )
        } else if (m.minAndroidApi < APPFUNCTIONS_REALISTIC_FLOOR) {
            issues += Issue(
                "MIN_API_BELOW_APPFUNCTIONS", Severity.WARNING, ManifestSchema.Field.MIN_ANDROID_API,
                "minAndroidApi=${m.minAndroidApi} 低于 AppFunctions 生态现实下限 " +
                    "$APPFUNCTIONS_REALISTIC_FLOOR —— 本地 MCP 绑定能力可能不可用"
            )
        }

        // ── 组件路径（格式层防穿越；存在性由 reader 对账）─────────────
        val components = m.components
        components.skills.forEachIndexed { i, dir ->
            if (!isSafeRelativePath(dir)) {
                issues += Issue(
                    "SKILL_PATH_UNSAFE", Severity.ERROR,
                    "${ManifestSchema.Field.COMPONENT_SKILLS}[$i]",
                    "skill 路径须为包内相对路径（不以 / 开头、无 .. 段），当前: \"$dir\""
                )
            }
        }
        components.mcpServers?.let { p ->
            if (!isSafeRelativePath(p)) issues += unsafeComponentIssue(ManifestSchema.Field.COMPONENT_MCP_SERVERS, p)
        }
        components.appFunctions?.let { p ->
            if (!isSafeRelativePath(p)) issues += unsafeComponentIssue(ManifestSchema.Field.COMPONENT_APP_FUNCTIONS, p)
        }
        components.hooks?.let { p ->
            if (!isSafeRelativePath(p)) issues += unsafeComponentIssue(ManifestSchema.Field.COMPONENT_HOOKS, p)
        }
        if (components.skills.isEmpty() && components.mcpServers == null &&
            components.appFunctions == null && components.hooks == null
        ) {
            issues += Issue(
                "COMPONENTS_EMPTY", Severity.WARNING, ManifestSchema.Field.COMPONENTS,
                "components 全空 —— 空壳插件（无任何可加载组件）"
            )
        }

        // ── 能力清单（安装时权限披露的数据源，取值域必须严格）─────────
        if (m.capabilities.isEmpty() && hasExecutableComponents(components)) {
            issues += Issue(
                "CAPABILITIES_EMPTY", Severity.ERROR, ManifestSchema.Field.CAPABILITIES,
                "含可执行组件（mcp/hooks）的插件必须声明 capabilities —— 权限披露无米下锅"
            )
        }
        val seenCapabilityIds = HashSet<String>()
        m.capabilities.forEachIndexed { i, cap ->
            val path = "${ManifestSchema.Field.CAPABILITIES}[$i]"
            if (cap.id.isEmpty()) {
                issues += Issue("CAP_ID_EMPTY", Severity.ERROR, "$path.${ManifestSchema.Field.CAPABILITY_ID}", "能力 id 不能为空")
            } else if (!CAPABILITY_ID_PATTERN.matches(cap.id)) {
                issues += Issue(
                    "CAP_ID_FORMAT", Severity.ERROR, "$path.${ManifestSchema.Field.CAPABILITY_ID}",
                    "能力 id 须为点分层（如 calendar.read），当前: \"${cap.id}\""
                )
            }
            if (!seenCapabilityIds.add(cap.id)) {
                issues += Issue(
                    "CAP_ID_DUPLICATE", Severity.ERROR, "$path.${ManifestSchema.Field.CAPABILITY_ID}",
                    "能力 id 重复: \"${cap.id}\""
                )
            }
            if (cap.risk !in ManifestSchema.RISK_LEVELS) {
                issues += Issue(
                    "CAP_RISK_INVALID", Severity.ERROR, "$path.${ManifestSchema.Field.CAPABILITY_RISK}",
                    "risk 须为 ${ManifestSchema.RISK_LEVELS} 之一，当前: \"${cap.risk}\""
                )
            }
            if (cap.description.isEmpty()) {
                issues += Issue(
                    "CAP_DESCRIPTION_MISSING", Severity.WARNING, "$path.${ManifestSchema.Field.CAPABILITY_DESCRIPTION}",
                    "能力描述缺省 —— 权限清单将显示占位文案，建议补齐"
                )
            }
        }

        // ── 签名元数据 ───────────────────────────────────────────────
        if (m.signature.algorithm.isNotEmpty() && m.signature.algorithm != ManifestSchema.SIGNATURE_ALGORITHM) {
            issues += Issue(
                "SIGNATURE_ALGORITHM", Severity.ERROR,
                "${ManifestSchema.Field.SIGNATURE}.${ManifestSchema.Field.SIGNATURE_ALGORITHM}",
                "签名算法唯一合法值为 ${ManifestSchema.SIGNATURE_ALGORITHM}，当前: \"${m.signature.algorithm}\""
            )
        }

        // ── 类目与标签 ───────────────────────────────────────────────
        if (m.category.isNotEmpty() && m.category !in ManifestSchema.CATEGORIES) {
            issues += Issue(
                "CATEGORY_UNKNOWN", Severity.WARNING, ManifestSchema.Field.CATEGORY,
                "类目 \"${m.category}\" 不在市场白名单 —— 归入 other 展示"
            )
        }
        if (m.tags.size > MAX_TAGS) {
            issues += Issue(
                "TAGS_OVERFLOW", Severity.WARNING, ManifestSchema.Field.TAGS,
                "tags 数量 ${m.tags.size} 超过 $MAX_TAGS —— 搜索索引只取前 $MAX_TAGS 个"
            )
        }

        return Result(
            errors = issues.filter { it.severity == Severity.ERROR },
            warnings = issues.filter { it.severity == Severity.WARNING },
        )
    }

    /**
     * 组件存在性对账（解包后调用）：manifest 声明的组件路径必须在包内
     * entries 中存在。目录类组件（skills、hooks）以「前缀命中」判定 ——
     * 目录声明指向目录下任意文件即算存在；文件类组件（mcpServers、
     * appFunctions）要求精确路径命中。
     *
     * @param entryPaths 包内全部文件路径集合（规范化后）
     */
    fun validateComponentPresence(
        m: AgentPluginManifest,
        entryPaths: Set<String>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()
        val components = m.components
        components.skills.forEachIndexed { i, dir ->
            val prefix = normalizeDirPrefix(dir)
            if (entryPaths.none { it.startsWith(prefix) }) {
                issues += Issue(
                    "SKILL_DIR_MISSING", Severity.ERROR,
                    "${ManifestSchema.Field.COMPONENT_SKILLS}[$i]",
                    "skill 目录 \"$dir\" 在包内不存在（前缀 \"$prefix\" 无命中）"
                )
            }
        }
        components.mcpServers?.let { p ->
            if (p !in entryPaths) {
                issues += Issue(
                    "MCP_SERVERS_MISSING", Severity.ERROR, ManifestSchema.Field.COMPONENT_MCP_SERVERS,
                    "mcpServers 文件 \"$p\" 在包内不存在"
                )
            }
        }
        components.appFunctions?.let { p ->
            if (p !in entryPaths) {
                issues += Issue(
                    "APP_FUNCTIONS_MISSING", Severity.ERROR, ManifestSchema.Field.COMPONENT_APP_FUNCTIONS,
                    "appFunctions 文件 \"$p\" 在包内不存在"
                )
            }
        }
        components.hooks?.let { p ->
            val prefix = normalizeDirPrefix(p)
            if (entryPaths.none { it.startsWith(prefix) }) {
                issues += Issue(
                    "HOOKS_DIR_MISSING", Severity.ERROR, ManifestSchema.Field.COMPONENT_HOOKS,
                    "hooks 目录 \"$p\" 在包内不存在"
                )
            }
        }
        return issues
    }

    // ── 内部工具 ─────────────────────────────────────────────────────

    /** 最低支持 API（低于它连市场客户端都跑不起来）。 */
    const val MIN_API_FLOOR = 21

    /** AppFunctions 生态现实下限（Spec：minAndroidApi 34+）。 */
    const val APPFUNCTIONS_REALISTIC_FLOOR = 34

    /** tags 上限（搜索索引膨胀防线）。 */
    const val MAX_TAGS = 12

    /** 能力 id 点分格式（calendar.read / tasks.write）。 */
    val CAPABILITY_ID_PATTERN = Regex("^[a-z][a-z0-9_-]*(\\.[a-z0-9_-]+)+$")

    /**
     * 包内相对路径合法性：非空、不以 / 开头、无驱动器冒号、无 NUL、
     * 任一路径段不是 . 或 ..、段内无反斜杠（zip 路径统一正斜杠）。
     */
    internal fun isSafeRelativePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/")) return false
        if ('\u0000' in path || ':' in path || '\\' in path) return false
        return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun unsafeComponentIssue(field: String, path: String) = Issue(
        "PATH_UNSAFE", Severity.ERROR, field,
        "组件路径须为包内相对路径（不以 / 开头、无 .. 段），当前: \"$path\""
    )

    /** 目录声明 → 前缀（补尾部斜杠；「skills/x」与「skills/x/」等价）。 */
    private fun normalizeDirPrefix(dir: String): String =
        if (dir.endsWith("/")) dir else "$dir/"

    private fun hasExecutableComponents(c: PluginComponents): Boolean =
        c.mcpServers != null || c.hooks != null
}
