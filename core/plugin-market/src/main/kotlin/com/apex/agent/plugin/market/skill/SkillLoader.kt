package com.apex.agent.plugin.market.skill

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.plugin.market.pkg.PluginPackage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Skill Loader —— 渐进式披露三阶段加载门面（Spec §4.1）。
 *
 * ## 阶段契约
 *
 * | 阶段 | API | 拿到什么 | 时机 |
 * |---|---|---|---|
 * | Discovery | [discover] | frontmatter（name/description/allowedTools）| 安装后 / 会话启动 |
 * | Activation | [activate] | 完整正文（digest 已复核）| 用户请求匹配 description |
 * | Execution | [executionAsset] | scripts 与 references 单文件 | 指令执行到需要时 |
 *
 * 「用户请求匹配 description」的判定属于 agent-engine 的提示词/检索层
 * （上层接线），loader 只提供**按 name 精确激活**与**按相对路径取资产**。
 *
 * ## 防御式纪律
 *
 * - 解析失败的 skill **跳过**（issues 留痕），不拖垮同包其它 skill；
 * - name 缺失 / 重复 → 跳过 + issue（name 是包内唯一键）；
 * - executionAsset 的路径穿越守卫与包层同规则（../ 与绝对路径拒绝）。
 */
class SkillLoader(
    /** 虚拟钟注入（审计时间戳；仓库纪律：不依赖墙钟）。 */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val source = "SkillLoader"

    /**
     * 包级 skill 完整性清单（Spec §4.2）—— 市场 Ingest 生成，被包级
     * SIGNATURE 覆盖（防篡改链条：改 SKILL.md 或改 lock 都会在
     * Activation 复核 / 包验签处失败）。
     *
     * 形态：`{"schema": "apex-skills-lock-v1", "digests": {"路径": "sha256hex"}}`
     * digest 不写在 SKILL.md frontmatter 里 —— digest 行会参与自身计算，
     * 自指无解。
     */
    companion object {
        const val SKILLS_LOCK_PATH = "skills-lock.json"
        private val lockJson = Json { ignoreUnknownKeys = true; isLenient = false }
    }

    /**
     * Discovery：解析包内全部 skill 的 frontmatter。
     *
     * skill 目录来自 manifest.components.skills（PR-A 已对账存在）；
     * 完整性 digest 从 [SKILLS_LOCK_PATH] 注入（存在时）。frontmatter
     * 缺 name 的 skill 跳过（description 缺省放行 —— 纯指令 skill
     * 可以没有描述，只是永远匹配不上 Activation）。
     */
    fun discover(pkg: PluginPackage): DiscoveryResult {
        val digests = readSkillsLock(pkg)
        val manifests = mutableListOf<SkillManifest>()
        val issues = mutableListOf<String>()
        val seenNames = HashSet<String>()

        for (dir in pkg.manifest.components.skills) {
            val normalized = dir.trimEnd('/')
            val skillPath = "$normalized/${SkillManifest.SKILL_FILE}"
            val bytes = pkg.entries[skillPath]
            if (bytes == null) {
                // 理论不可达（reader 已对账目录前缀存在），防御式兜底
                issues.add("skill 目录 \"$normalized\" 无 ${SkillManifest.SKILL_FILE}")
                continue
            }
            val parsed = SkillFrontmatter.parse(bytes.toString(Charsets.UTF_8))
            val name = parsed.scalars["name"]?.trim().orEmpty()
            if (name.isEmpty()) {
                issues.add("skill \"$normalized\" frontmatter 缺 name —— 跳过")
                continue
            }
            if (!seenNames.add(name)) {
                issues.add("skill name \"$name\" 包内重复（$normalized）—— 跳过后者")
                continue
            }
            manifests += SkillManifest(
                name = name,
                description = parsed.scalars["description"]?.trim().orEmpty(),
                skillDir = normalized,
                license = parsed.scalars["license"]?.trim()?.takeIf { it.isNotEmpty() },
                allowedTools = parsed.lists["allowed-tools"].orEmpty(),
                integrityDigest = digests[skillPath],
            )
        }

        if (issues.isNotEmpty()) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, source,
                "Discovery 降级（${issues.size} 处）: ${issues.joinToString("; ")}"
            )
        }
        return DiscoveryResult(manifests.toList(), issues.toList())
    }

    /**
     * Activation：加载完整正文 —— 加载前强制完整性复核（Spec §4.2）。
     *
     * @throws SkillIntegrityException digest 声明在 lock 但与 SKILL.md
     *   实际字节不符 —— 拒绝加载（调用方折叠为该 skill 不可用）
     */
    fun activate(pkg: PluginPackage, manifest: SkillManifest): SkillDocument {
        val skillPath = "${manifest.skillDir}/${SkillManifest.SKILL_FILE}"
        val bytes = pkg.entries[skillPath]
            ?: throw IllegalStateException("skill 文件缺失: $skillPath")
        SkillIntegrity.verify(manifest, bytes)
        val parsed = SkillFrontmatter.parse(bytes.toString(Charsets.UTF_8))
        return SkillDocument(
            manifest = manifest,
            body = parsed.body,
            activatedAtMs = clock(),
        )
    }

    /**
     * Execution：按需读取 skill 目录下的执行资产（scripts 与 references）。
     *
     * @param relPath 相对 skill 目录的路径（如 scripts/build.sh、references/api.md）
     * @return 资产字节；路径越界 / 不存在 / 指向 SKILL.md 自身 → null
     */
    fun executionAsset(pkg: PluginPackage, manifest: SkillManifest, relPath: String): ByteArray? {
        val clean = relPath.trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("/") || clean.contains("..") || clean.contains('\\')) return null
        if (clean == SkillManifest.SKILL_FILE) return null  // 正文走 activate，不作为资产暴露
        val fullPath = "${manifest.skillDir}/$clean"
        return pkg.entries[fullPath]
    }

    /**
     * 读包级 skills-lock.json（存在时）；缺失/畸形 → 空 map（本地开发包
     * 无 lock 即无契约，放行）。防御式：任何形状吞得下。
     */
    private fun readSkillsLock(pkg: PluginPackage): Map<String, String> {
        val bytes = pkg.entries[SKILLS_LOCK_PATH] ?: return emptyMap()
        return try {
            val root = lockJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            val digests = root["digests"]?.jsonObject ?: return emptyMap()
            digests.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, source,
                "skills-lock.json 解析失败（折叠为空，完整性契约降级）: ${e.message}"
            )
            emptyMap()
        }
    }

    /** Discovery 结果：可用清单 + 降级痕迹。 */
    data class DiscoveryResult(
        val manifests: List<SkillManifest>,
        val issues: List<String>,
    )
}
