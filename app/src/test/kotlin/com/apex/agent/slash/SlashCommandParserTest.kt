package com.apex.agent.slash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommandParser].
 *
 * These cover the grammar `/<type>:<id> [key=value ...] [positional user
 * text ...]` plus the documented degradation paths (missing slash, missing
 * colon, empty id, unknown type, malformed kv tokens). The parser is pure
 * JVM so the tests run without an Android or Compose runtime.
 *
 * Theocracy：skill / mcp / connector / logic / fusion 命令类型已随五域
 * 剥离移除，当前唯一结构化类型是 plugin（APK 插件 / 功能性插件指令）。
 */
class SlashCommandParserTest {

    // ═══════════════════════════════════════════════════════════
    // Happy paths — known command type
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `bare plugin command parses to Plugin with empty args`() {
        val parsed = SlashCommandParser.parse("/plugin:pdf_reader")

        assertTrue(parsed is SlashCommand.Plugin)
        val plugin = parsed as SlashCommand.Plugin
        assertEquals("pdf_reader", plugin.id)
        assertEquals(emptyMap<String, String>(), plugin.args)
        assertEquals("", plugin.userExtra)
    }

    @Test
    fun `removed domain command types degrade to Unknown`() {
        // 五域剥离后 skill / mcp / connector / fusion / logic 不再是结构化
        // 类型 —— 解析必须诚实降级为 Unknown（原文透传给 agent），绝不
        // 静默吞掉或误路由。
        listOf("skill", "mcp", "connector", "fusion", "logic").forEach { type ->
            val parsed = SlashCommandParser.parse("/$type:foo")
            assertTrue("type=$type should be Unknown", parsed is SlashCommand.Unknown)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Args + positional user text
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `kv args and positional user text are split correctly`() {
        val parsed = SlashCommandParser.parse("/plugin:pdf_reader file=/sdcard/a.pdf latest report")

        assertTrue(parsed is SlashCommand.Plugin)
        val plugin = parsed as SlashCommand.Plugin
        assertEquals("pdf_reader", plugin.id)
        assertEquals(mapOf("file" to "/sdcard/a.pdf"), plugin.args)
        assertEquals("latest report", plugin.userExtra)
    }

    @Test
    fun `multiple kv args are collected in insertion order`() {
        val parsed = SlashCommandParser.parse("/plugin:reader file=a.pdf page=3")

        val plugin = parsed as SlashCommand.Plugin
        assertEquals("reader", plugin.id)
        assertEquals(mapOf("file" to "a.pdf", "page" to "3"), plugin.args)
        assertEquals("", plugin.userExtra)
    }

    @Test
    fun `kv value may contain equals signs`() {
        // Regex captures key=([A-Za-z_][\w.\-]*) and value=(.*) ; the value
        // side is greedy, so "a=b=c" → key=a, value="b=c".
        val parsed = SlashCommandParser.parse("/plugin:foo filter=a=b=c")

        val plugin = parsed as SlashCommand.Plugin
        assertEquals(mapOf("filter" to "a=b=c"), plugin.args)
    }

    @Test
    fun `kv parsing stops once positional text begins`() {
        // After the first positional token, subsequent key=value-shaped tokens
        // are treated as user text (CLI-style) rather than dropped into args.
        //（修复：原输入没有 positional token，永远走不到“停止 kv”分支 —— 补一个真 token）
        val parsed = SlashCommandParser.parse("/plugin:foo keep=this run drop=that")

        val plugin = parsed as SlashCommand.Plugin
        assertEquals(mapOf("keep" to "this"), plugin.args)
        assertEquals("run drop=that", plugin.userExtra)
    }

    @Test
    fun `kv key must start with letter or underscore`() {
        // "1key=val" doesn't match the key regex → positional user text；
        // 且按 CLI 语义 kv 随即停止 —— 后续合法 kv 也归入 user text。
        val parsed = SlashCommandParser.parse("/plugin:foo 1key=val real=v")

        val plugin = parsed as SlashCommand.Plugin
        assertEquals(emptyMap<String, String>(), plugin.args)
        assertEquals("1key=val real=v", plugin.userExtra)
    }

    @Test
    fun `user extra text collapses whitespace runs like sh tokenization`() {
        // 缺陷 7 修复后的 sh-like 词法：空白序列视为单分隔符（引号内除外）。
        val parsed = SlashCommandParser.parse("/plugin:foo   multiple    spaces   here")

        val plugin = parsed as SlashCommand.Plugin
        assertEquals("multiple spaces here", plugin.userExtra)
    }

    @Test
    fun `quoted kv value survives spaces`() {
        // sh 词法：双引号内空格不切 token，引号被消费。
        val parsed = SlashCommandParser.parse("""/plugin:foo query="a b c" tail""")

        val plugin = parsed as SlashCommand.Plugin
        assertEquals(mapOf("query" to "a b c"), plugin.args)
        assertEquals("tail", plugin.userExtra)
    }

    // ═══════════════════════════════════════════════════════════
    // Whitespace tolerance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `leading whitespace is tolerated`() {
        val parsed = SlashCommandParser.parse("   /plugin:pdf_reader")

        assertTrue(parsed is SlashCommand.Plugin)
        assertEquals("pdf_reader", (parsed as SlashCommand.Plugin).id)
    }

    @Test
    fun `trailing whitespace is trimmed from id`() {
        val parsed = SlashCommandParser.parse("/plugin:pdf_reader   ")

        assertTrue(parsed is SlashCommand.Plugin)
        assertEquals("pdf_reader", (parsed as SlashCommand.Plugin).id)
    }

    // ═══════════════════════════════════════════════════════════
    // Degradation to Unknown
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `input without leading slash is Unknown`() {
        val parsed = SlashCommandParser.parse("plugin:missing_slash")

        assertTrue(parsed is SlashCommand.Unknown)
        assertEquals("plugin:missing_slash", (parsed as SlashCommand.Unknown).raw)
    }

    @Test
    fun `input with missing colon is Unknown`() {
        val parsed = SlashCommandParser.parse("/plugin")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `input with empty id is Unknown`() {
        val parsed = SlashCommandParser.parse("/plugin:")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `input with empty type is Unknown`() {
        // "/:foo" → colon at index 1 → type substring is empty.
        val parsed = SlashCommandParser.parse("/:foo")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `input with unknown type is Unknown`() {
        val parsed = SlashCommandParser.parse("/unknown:foo")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `unknown command forwards raw text verbatim`() {
        val parsed = SlashCommandParser.parse("/help")

        assertTrue(parsed is SlashCommand.Unknown)
        assertEquals("/help", (parsed as SlashCommand.Unknown).raw)
    }

    // ═══════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `looksLikeSlash detects leading slash after whitespace`() {
        assertTrue(SlashCommand.looksLikeSlash("/plugin:foo"))
        assertTrue(SlashCommand.looksLikeSlash("   /plugin:foo"))
        assertTrue(!SlashCommand.looksLikeSlash("plugin:foo"))
        assertTrue(!SlashCommand.looksLikeSlash(""))
    }

    @Test
    fun `supported types list is stable and ordered`() {
        // Order matters for slash-menu rendering; assert it explicitly so a
        // future reorder is a conscious decision rather than an accident.
        // Theocracy：仅剩 plugin（skill/mcp/connector/logic/fusion 随五域剥离移除）。
        assertEquals(
            listOf("plugin"),
            SlashCommand.SUPPORTED_TYPES
        )
    }
}
