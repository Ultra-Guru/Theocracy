package com.apex.agent.plugin.market.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ManifestValidator] 契约测试：语义层全规则覆盖（每条 ERROR/WARNING 规则
 * 至少一个正例 + 一个反例），组件存在性对账含「漏签目录/前缀命中」分支。
 */
class ManifestValidatorTest {

    private fun valid() = AgentPluginManifest(
        schemaVersion = "1.0",
        id = "com.example.helper",
        name = "helper",
        displayName = "助手",
        version = "1.0.0",
        minAndroidApi = 34,
        components = PluginComponents(
            skills = listOf("skills/summarize"),
            mcpServers = "mcp/servers.json",
        ),
        capabilities = listOf(PluginCapability(id = "calendar.read", risk = "R1", description = "读日历")),
    )

    // ── 合法基线 ────────────────────────────────────────────────────

    @Test
    fun `valid manifest passes with no issues`() {
        val r = ManifestValidator.validate(valid())
        assertTrue("合法样例不应有 error: ${r.errors}", r.isValid)
        assertTrue("合法样例不应有 warning: ${r.warnings}", r.warnings.isEmpty())
    }

    // ── 身份与版本（ERROR 规则）─────────────────────────────────────

    @Test
    fun `schema version mismatch is error`() {
        val r = ManifestValidator.validate(valid().copy(schemaVersion = "2.0"))
        assertFalse(r.isValid)
        assertEquals("SCHEMA_VERSION", r.errors.single().code)
    }

    @Test
    fun `bad id format is error`() {
        // 注意：两段反向域名（com.example）是合法 id —— 不在 bad list
        listOf("", "Meeting-Helper", "single", "com.EXAMPLE.helper").forEach { badId ->
            val r = ManifestValidator.validate(valid().copy(id = badId))
            assertFalse("id=\"$badId\" 应被拒", r.isValid)
            assertTrue(r.errors.any { it.code.startsWith("ID_") })
        }
    }

    @Test
    fun `two-segment reverse domain id is valid`() {
        assertTrue(ManifestValidator.validate(valid().copy(id = "com.example")).isValid)
    }

    @Test
    fun `bad name slug is error`() {
        val r = ManifestValidator.validate(valid().copy(name = "Meeting_Helper!"))
        assertFalse(r.isValid)
        assertEquals("NAME_FORMAT", r.errors.single().code)
    }

    @Test
    fun `non-semver version is error`() {
        listOf("", "1.2", "v1.2.0", "latest").forEach { bad ->
            val r = ManifestValidator.validate(valid().copy(version = bad))
            assertFalse("version=\"$bad\" 应被拒", r.isValid)
            assertTrue(r.errors.any { it.code.startsWith("VERSION_") })
        }
    }

    @Test
    fun `prerelease semver is accepted`() {
        val r = ManifestValidator.validate(valid().copy(version = "1.0.0-rc.1+build.5"))
        assertTrue(r.isValid)
    }

    // ── 平台门槛 ────────────────────────────────────────────────────

    @Test
    fun `min api below 21 is error`() {
        val r = ManifestValidator.validate(valid().copy(minAndroidApi = 19))
        assertFalse(r.isValid)
        assertEquals("MIN_API_BELOW_FLOOR", r.errors.single().code)
    }

    @Test
    fun `min api below appfunctions floor is warning only`() {
        val r = ManifestValidator.validate(valid().copy(minAndroidApi = 28))
        assertTrue("API 28 应可上架（仅 warning）", r.isValid)
        assertEquals("MIN_API_BELOW_APPFUNCTIONS", r.warnings.single().code)
    }

    // ── 组件路径防穿越（ERROR）──────────────────────────────────────

    @Test
    fun `escaping component paths are rejected`() {
        val escaping = listOf("../outside", "/abs/path", "a/../../b", "c:/win", "back\\slash")
        escaping.forEach { bad ->
            val r = ManifestValidator.validate(
                valid().copy(components = PluginComponents(skills = listOf(bad)))
            )
            assertFalse("skill 路径 \"$bad\" 应被拒", r.isValid)
            assertEquals("SKILL_PATH_UNSAFE", r.errors.single().code)
        }
        listOf("../mcp.json", "/root/mcp.json").forEach { bad ->
            val r = ManifestValidator.validate(
                valid().copy(components = PluginComponents(mcpServers = bad))
            )
            assertFalse(r.isValid)
            assertEquals("PATH_UNSAFE", r.errors.single().code)
        }
    }

    @Test
    fun `empty components is warning not error`() {
        val r = ManifestValidator.validate(valid().copy(components = PluginComponents()))
        assertTrue("空壳插件可上架（warning 标注）", r.isValid)
        assertEquals("COMPONENTS_EMPTY", r.warnings.single().code)
    }

    // ── 能力清单 ────────────────────────────────────────────────────

    @Test
    fun `executable components without capabilities is error`() {
        val r = ManifestValidator.validate(
            valid().copy(capabilities = emptyList())
        )  // valid() 含 mcpServers —— 可执行组件必须有 capabilities
        assertFalse(r.isValid)
        assertEquals("CAPABILITIES_EMPTY", r.errors.single().code)
    }

    @Test
    fun `skills-only plugin without capabilities is acceptable`() {
        // 纯工作流插件（skills-only）无外部工具 —— capabilities 可空（Spec §4.3 形态）
        val r = ManifestValidator.validate(
            valid().copy(capabilities = emptyList(), components = PluginComponents(skills = listOf("skills/s")))
        )
        assertTrue(r.isValid)
    }

    @Test
    fun `invalid risk level is error`() {
        val r = ManifestValidator.validate(
            valid().copy(capabilities = listOf(PluginCapability(id = "x.read", risk = "R9", description = "d")))
        )
        assertFalse(r.isValid)
        assertEquals("CAP_RISK_INVALID", r.errors.single().code)
    }

    @Test
    fun `all four risk levels are in domain`() {
        // R0..R3 全取值域合法（Spec §8.1 四档）
        val caps = listOf("R0", "R1", "R2", "R3").mapIndexed { i, risk ->
            PluginCapability(id = "domain.c$i", risk = risk, description = "d")
        }
        val r = ManifestValidator.validate(valid().copy(capabilities = caps))
        assertTrue(r.isValid)
    }

    @Test
    fun `duplicate capability ids are error`() {
        val caps = listOf(
            PluginCapability(id = "dup.read", risk = "R0", description = "a"),
            PluginCapability(id = "dup.read", risk = "R1", description = "b"),
        )
        val r = ManifestValidator.validate(valid().copy(capabilities = caps))
        assertFalse(r.isValid)
        assertEquals("CAP_ID_DUPLICATE", r.errors.single().code)
    }

    @Test
    fun `missing capability description is warning`() {
        val r = ManifestValidator.validate(
            valid().copy(capabilities = listOf(PluginCapability(id = "x.read", risk = "R0")))
        )
        assertTrue(r.isValid)
        assertEquals("CAP_DESCRIPTION_MISSING", r.warnings.single().code)
    }

    // ── 签名元数据与类目 ────────────────────────────────────────────

    @Test
    fun `non-es256 algorithm is error`() {
        val r = ManifestValidator.validate(valid().copy(signature = PluginSignatureMeta(algorithm = "RS256")))
        assertFalse(r.isValid)
        assertEquals("SIGNATURE_ALGORITHM", r.errors.single().code)
    }

    @Test
    fun `unknown category is warning`() {
        val r = ManifestValidator.validate(valid().copy(category = "weird"))
        assertTrue(r.isValid)
        assertEquals("CATEGORY_UNKNOWN", r.warnings.single().code)
    }

    @Test
    fun `tag overflow is warning`() {
        val r = ManifestValidator.validate(valid().copy(tags = (1..13).map { "t$it" }))
        assertTrue(r.isValid)
        assertEquals("TAGS_OVERFLOW", r.warnings.single().code)
    }

    // ── 组件存在性对账（解包后）─────────────────────────────────────

    @Test
    fun `component presence matches by prefix and exact path`() {
        val m = valid()
        val entries = setOf(
            "skills/summarize/SKILL.md",
            "skills/summarize/scripts/x.sh",
            "mcp/servers.json",
            "README.md",
        )
        assertTrue("全部声明组件在包内存在", ManifestValidator.validateComponentPresence(m, entries).isEmpty())
    }

    @Test
    fun `missing skill dir is error`() {
        val issues = ManifestValidator.validateComponentPresence(valid(), setOf("mcp/servers.json"))
        assertEquals(listOf("SKILL_DIR_MISSING"), issues.map { it.code })
    }

    @Test
    fun `missing mcp servers file is error`() {
        val issues = ManifestValidator.validateComponentPresence(valid(), setOf("skills/summarize/SKILL.md"))
        assertEquals(listOf("MCP_SERVERS_MISSING"), issues.map { it.code })
    }

    @Test
    fun `skill dir with trailing slash equivalence`() {
        // 「skills/summarize」与「skills/summarize/」声明等价（前缀归一）
        val m = valid().copy(components = PluginComponents(skills = listOf("skills/summarize/")))
        val issues = ManifestValidator.validateComponentPresence(m, setOf("skills/summarize/SKILL.md"))
        assertTrue(issues.isEmpty())
    }
}
