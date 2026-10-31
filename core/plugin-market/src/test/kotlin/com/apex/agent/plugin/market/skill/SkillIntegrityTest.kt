package com.apex.agent.plugin.market.skill

import com.apex.agent.plugin.market.pkg.PackageBuilder
import com.apex.agent.plugin.market.pkg.PackageReadResult
import com.apex.agent.plugin.market.pkg.PluginPackage
import com.apex.agent.plugin.market.pkg.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [SkillIntegrity] 契约测试（Spec §4.2，MCP Skills extension 完整性模型）：
 * lock 注入放行、篡改拒绝、无 lock 放行、大小写不敏感、跨层算法一致。
 */
class SkillIntegrityTest {

    private val skillBody = "# 正文：工作流指令"
    private val skillPath = "skills/summarize/SKILL.md"

    private fun skillMd() = "---\nname: summarize\ndescription: d\n---\n\n$skillBody"

    /** 构包（可选附 skills-lock.json，digest 按 SKILL.md 实际字节计算）。 */
    private fun pkgWithLock(withLock: Boolean, lockOverride: String? = null): PluginPackage {
        val skillBytes = skillMd().toByteArray(Charsets.UTF_8)
        val manifest = PackageBuilder.sampleManifest().copy(
            components = com.apex.agent.plugin.market.format.PluginComponents(
                skills = listOf("skills/summarize")
            )
        )
        val builder = PackageBuilder().manifest(manifest)
            .file(skillPath, skillBytes)
        if (withLock) {
            val lock = lockOverride ?: """
                {"schema": "apex-skills-lock-v1", "digests": {"$skillPath": "${SkillIntegrity.digestOf(skillBytes)}"}}
            """.trimIndent()
            builder.file(SkillLoader.SKILLS_LOCK_PATH, lock)
        }
        return (PluginPackageReader.read(builder.build()) as PackageReadResult.Success).pkg
    }

    @Test
    fun `matching lock digest activates`() {
        val pkg = pkgWithLock(withLock = true)
        val loader = SkillLoader()
        val m = loader.discover(pkg).manifests.single()
        assertNotNull("lock digest 应注入 manifest", m.integrityDigest)
        val doc = loader.activate(pkg, m)
        assertTrue(doc.body.contains("工作流指令"))
    }

    @Test
    fun `tampered body is rejected at activation`() {
        val original = pkgWithLock(withLock = true)
        val loader = SkillLoader()
        val m = loader.discover(original).manifests.single()
        val declared = m.integrityDigest!!

        // 篡改：SKILL.md 字节被换（lock 仍声明旧 digest）
        val tamperedEntries = original.entries.toMutableMap()
        tamperedEntries[skillPath] = "---\nname: summarize\ndescription: d\n---\n\n# 被篡改".toByteArray(Charsets.UTF_8)
        val tamperedPkg = PluginPackage(original.manifest, tamperedEntries)
        try {
            loader.activate(tamperedPkg, m)
            fail("digest 不匹配必须抛 SkillIntegrityException")
        } catch (e: SkillIntegrityException) {
            assertEquals("summarize", e.manifest.name)
            assertEquals(declared, e.declaredDigest)
            assertTrue(e.actualDigest != declared)
            assertTrue(e.message!!.contains("拒绝加载"))
        }
    }

    @Test
    fun `absent lock file passes through`() {
        // 本地开发包无 skills-lock.json —— 无契约即放行（包签名守分发面）
        val pkg = pkgWithLock(withLock = false)
        val loader = SkillLoader()
        val m = loader.discover(pkg).manifests.single()
        assertEquals(null, m.integrityDigest)
        val doc = loader.activate(pkg, m)
        assertTrue(doc.body.contains("工作流指令"))
    }

    @Test
    fun `malformed lock degrades to no-contract`() {
        // lock 文件畸形（非 JSON）→ 防御式折叠为空 map（AppLogger 留痕）
        val pkg = pkgWithLock(withLock = true, lockOverride = "{ broken json")
        val loader = SkillLoader()
        val m = loader.discover(pkg).manifests.single()
        assertEquals("畸形 lock 降级为无契约", null, m.integrityDigest)
        assertTrue(loader.activate(pkg, m).body.contains("工作流指令"))
    }

    @Test
    fun `lock covering other skill only does not constrain this one`() {
        // lock 只记录其它路径 —— 本 skill 无契约条目 → 放行
        val pkg = pkgWithLock(
            withLock = true,
            lockOverride = """{"schema": "apex-skills-lock-v1", "digests": {"skills/other/SKILL.md": "00"}}"""
        )
        val loader = SkillLoader()
        val m = loader.discover(pkg).manifests.single()
        assertEquals(null, m.integrityDigest)
    }

    @Test
    fun `digest comparison is case insensitive`() {
        // 市场后端摘要工具大小写不一（hex 大写）—— 复核容忍
        val skillBytes = skillMd().toByteArray(Charsets.UTF_8)
        val upper = SkillIntegrity.digestOf(skillBytes).uppercase()
        val manifest = SkillManifest("summarize", "d", "skills/summarize", integrityDigest = upper)
        SkillIntegrity.verify(manifest, skillBytes)  // 不抛即通过
    }

    @Test
    fun `digest helper matches package-level codec`() {
        // 与 PR-A SignatureCodec.sha256Hex 同算法 —— 跨层一致性锚点
        val bytes = "theocracy".toByteArray(Charsets.UTF_8)
        assertEquals(
            com.apex.agent.plugin.market.pkg.SignatureCodec.sha256Hex(bytes),
            SkillIntegrity.digestOf(bytes),
        )
    }
}
