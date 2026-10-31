package com.apex.agent.plugin.market.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SkillFrontmatter] 契约测试：合法子集 / 无头部 / 未闭合 / 畸形行四形态。
 * 断言值按解析器实现逐条核实（AGENTS.md：断言值先读源码，不臆造）。
 */
class SkillFrontmatterTest {

    @Test
    fun `well-formed frontmatter parses scalars lists and body`() {
        val md = """
            ---
            name: summarize
            description: 会议纪要整理，含待办提取
            license: Apache-2.0
            allowed-tools: [Read, Write, Edit]
            ---

            # 整理流程

            1. 通读全文
            2. 提取行动项
        """.trimIndent()
        val r = SkillFrontmatter.parse(md)
        assertEquals("summarize", r.scalars["name"])
        assertEquals("会议纪要整理，含待办提取", r.scalars["description"])
        assertEquals("Apache-2.0", r.scalars["license"])
        assertEquals(listOf("Read", "Write", "Edit"), r.lists["allowed-tools"])
        assertTrue("畸形行应为零", r.issues.isEmpty())
        assertTrue("正文应从标题开始", r.body.trimStart().startsWith("# 整理流程"))
        assertTrue("正文不含 frontmatter 键", !r.body.contains("name:"))
    }

    @Test
    fun `dash list form folds into same list field`() {
        // 短行列表（- item）与逗号形态等价收敛
        val md = """
            ---
            name: review
            description: 代码评审
            allowed-tools:
              - Read
              - Grep
              - Glob
            ---

            body
        """.trimIndent()
        val r = SkillFrontmatter.parse(md)
        assertEquals(listOf("Read", "Grep", "Glob"), r.lists["allowed-tools"])
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `text without frontmatter is all body`() {
        val md = "# 纯指令 skill\n\n直接正文，无元数据。"
        val r = SkillFrontmatter.parse(md)
        assertTrue(r.scalars.isEmpty())
        assertTrue(r.lists.isEmpty())
        assertEquals(md, r.body)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `unclosed frontmatter degrades to empty with issue`() {
        val md = "---\nname: broken\ndescription: 缺闭合定界符\n\n正文永远到不了"
        val r = SkillFrontmatter.parse(md)
        assertTrue(r.scalars.isEmpty())
        assertTrue(r.issues.single().contains("未闭合"))
        assertEquals("", r.body)
    }

    @Test
    fun `malformed lines are skipped with issues`() {
        val md = """
            ---
            name: mixed
            这一行没有冒号
            : 空键行
            description: 合法值
            name: duplicate-key
            ---

            body
        """.trimIndent()
        val r = SkillFrontmatter.parse(md)
        assertEquals("mixed", r.scalars["name"])  // 重复键后值忽略
        assertEquals("合法值", r.scalars["description"])
        assertEquals(3, r.issues.size)  // 无冒号行 + 空键行 + 重复键
        assertEquals("body", r.body.trim())
    }

    @Test
    fun `orphan dash item without key is skipped`() {
        val md = "---\n- 孤儿列表项\nname: x\n---\nbody"
        val r = SkillFrontmatter.parse(md)
        assertEquals("x", r.scalars["name"])
        assertTrue(r.issues.single().contains("孤立列表项"))
    }

    @Test
    fun `bare comma value stays scalar`() {
        // 裸逗号在 YAML 里是标量 —— 含逗号描述不误拆为列表
        val md = "---\ndescription: Hello, world and more\nname: greet\n---\nbody"
        val r = SkillFrontmatter.parse(md)
        assertEquals("Hello, world and more", r.scalars["description"])
        assertTrue(r.lists.isEmpty())
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `flow list with empty segments tolerates`() {
        // [Read,, Write] 空段过滤
        val md = "---\nallowed-tools: [Read,, Write]\nname: x\n---\nbody"
        val r = SkillFrontmatter.parse(md)
        assertEquals(listOf("Read", "Write"), r.lists["allowed-tools"])
    }

    @Test
    fun `empty header yields empty fields`() {
        val md = "---\n---\nbody only"
        val r = SkillFrontmatter.parse(md)
        assertTrue(r.scalars.isEmpty())
        assertEquals("body only", r.body)
    }

    @Test
    fun `crlf line endings are tolerated`() {
        val md = "---\r\nname: crlf\r\ndescription: win 风格换行\r\n---\r\n\r\n正文"
        val r = SkillFrontmatter.parse(md)
        assertEquals("crlf", r.scalars["name"])
        assertEquals("win 风格换行", r.scalars["description"])
        assertTrue(r.body.contains("正文"))
    }

    @Test
    fun `delimiter must be exact line`() {
        // 正文中的 --- 分隔线不构成 frontmatter（首行必须是定界符）
        val md = "标题\n\n---\n\n正文分隔线之后的 ---"
        val r = SkillFrontmatter.parse(md)
        assertEquals(md, r.body)  // 整体当正文
        assertTrue(r.issues.isEmpty())
    }
}
