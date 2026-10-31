package com.apex.agent.plugin.market.skill

import com.apex.agent.plugin.market.format.PluginComponents
import com.apex.agent.plugin.market.pkg.PackageBuilder
import com.apex.agent.plugin.market.pkg.PackageReadResult
import com.apex.agent.plugin.market.pkg.PluginPackage
import com.apex.agent.plugin.market.pkg.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillLoader] 渐进式披露契约测试（Spec §4.1/§4.2）：
 * 三阶段可见性（Discovery 拿不到正文）、防御式跳过、执行资产守卫。
 */
class SkillLoaderProgressiveTest {

    private val loader = SkillLoader(clock = { 1_720_000_000_000L })

    private fun skillMd(
        name: String,
        description: String = "描述",
        extra: String = "",
        body: String = "# 正文：内部工作流指令（不应在 Discovery 可见）",
    ) = "---\nname: $name\ndescription: $description$extra\n---\n\n$body"

    /** 构包并读出（不签名 —— Skill 层完整性由 digest 承担，包签名是市场分发面）。 */
    private fun pkgOf(
        skills: List<String> = listOf("skills/summarize"),
        vararg files: Pair<String, String>,
    ): PluginPackage {
        val manifest = PackageBuilder.sampleManifest().copy(
            components = PluginComponents(skills = skills)
        )
        val builder = PackageBuilder().manifest(manifest)
        files.forEach { (path, text) -> builder.file(path, text) }
        return (PluginPackageReader.read(builder.build()) as PackageReadResult.Success).pkg
    }

    @Test
    fun `discovery exposes only frontmatter not body`() {
        val pkg = pkgOf(files = arrayOf("skills/summarize/SKILL.md" to skillMd("summarize", "会议纪要整理")))
        val discovery = loader.discover(pkg)
        assertEquals(1, discovery.manifests.size)
        val m = discovery.manifests.single()
        assertEquals("summarize", m.name)
        assertEquals("会议纪要整理", m.description)
        assertEquals("skills/summarize", m.skillDir)
        // 渐进式披露核心断言：Discovery 结果是 SkillManifest ——
        // 该类型在形状上没有 body 字段（编译期保证），运行时再验一次不可达
        assertTrue(m.javaClass.declaredFields.none { it.name == "body" })
    }

    @Test
    fun `activation loads verified body`() {
        val pkg = pkgOf(files = arrayOf("skills/summarize/SKILL.md" to skillMd("summarize")))
        val m = loader.discover(pkg).manifests.single()
        val doc = loader.activate(pkg, m)
        assertTrue(doc.body.contains("内部工作流指令"))
        assertEquals(1_720_000_000_000L, doc.activatedAtMs)  // 虚拟钟注入
    }

    @Test
    fun `skill without name is skipped defensively`() {
        val pkg = pkgOf(
            files = arrayOf("skills/summarize/SKILL.md" to "---\ndescription: 缺 name\n---\nbody")
        )
        val discovery = loader.discover(pkg)
        assertTrue(discovery.manifests.isEmpty())
        assertTrue(discovery.issues.single().contains("缺 name"))
    }

    @Test
    fun `malformed skill does not kill siblings`() {
        // 两个 skill 目录：一个缺 name（跳过），一个合法（存活）
        val pkg = pkgOf(
            skills = listOf("skills/summarize", "skills/review"),
            "skills/summarize/SKILL.md" to "---\ndescription: 缺 name\n---\nbody",
            "skills/review/SKILL.md" to skillMd("review", "代码评审"),
        )
        val discovery = loader.discover(pkg)
        assertEquals(1, discovery.manifests.size)
        assertEquals("review", discovery.manifests.single().name)
        assertTrue(discovery.issues.single().contains("缺 name"))
    }

    @Test
    fun `duplicate skill names keep first`() {
        val pkg = pkgOf(
            skills = listOf("skills/summarize", "skills/review"),
            "skills/summarize/SKILL.md" to skillMd("dup-name", "第一个"),
            "skills/review/SKILL.md" to skillMd("dup-name", "第二个"),
        )
        val discovery = loader.discover(pkg)
        assertEquals(1, discovery.manifests.size)
        assertEquals("第一个", discovery.manifests.single().description)
        assertTrue(discovery.issues.single().contains("重复"))
    }

    @Test
    fun `execution assets load on demand with guards`() {
        val pkg = pkgOf(
            files = arrayOf(
                "skills/summarize/SKILL.md" to skillMd("summarize"),
                "skills/summarize/scripts/build.sh" to "#!/system/bin/sh\necho build",
                "skills/summarize/references/api.md" to "# API 参考",
            )
        )
        val m = loader.discover(pkg).manifests.single()

        val script = loader.executionAsset(pkg, m, "scripts/build.sh")
        assertEquals("#!/system/bin/sh\necho build", script!!.toString(Charsets.UTF_8))
        val ref = loader.executionAsset(pkg, m, "references/api.md")
        assertEquals("# API 参考", ref!!.toString(Charsets.UTF_8))
    }

    @Test
    fun `execution asset rejects traversal and skill file`() {
        val pkg = pkgOf(
            files = arrayOf(
                "skills/summarize/SKILL.md" to skillMd("summarize"),
                "skills/summarize/scripts/x.sh" to "x",
            )
        )
        val m = loader.discover(pkg).manifests.single()

        assertNull("父段穿越", loader.executionAsset(pkg, m, "../../other/SKILL.md"))
        assertNull("绝对路径", loader.executionAsset(pkg, m, "/etc/passwd"))
        assertNull("反斜杠", loader.executionAsset(pkg, m, "scripts\\x.sh"))
        assertNull("SKILL.md 不作资产暴露", loader.executionAsset(pkg, m, "SKILL.md"))
        assertNull("不存在", loader.executionAsset(pkg, m, "scripts/missing.sh"))
    }

    @Test
    fun `allowed-tools flows from frontmatter`() {
        val pkg = pkgOf(
            files = arrayOf(
                "skills/summarize/SKILL.md" to skillMd("summarize", extra = "\nallowed-tools: [Read, Write]")
            )
        )
        val m = loader.discover(pkg).manifests.single()
        assertEquals(listOf("Read", "Write"), m.allowedTools)
    }
}
