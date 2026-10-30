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
 * Theocracy：skill / mcp / fusion 命令域已剥离（重新设计后再接回）——
 * 现存类型 Connector / Plugin；语法与降级测试改用 connector 载体。
 */
class SlashCommandParserTest {

    // ═══════════════════════════════════════════════════════════
    // Happy paths — one per known command type
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `bare connector command parses to Connector`() {
        val parsed = SlashCommandParser.parse("/connector:ssh")

        assertTrue(parsed is SlashCommand.Connector)
        val connector = parsed as SlashCommand.Connector
        assertEquals("ssh", connector.id)
        assertEquals(emptyMap<String, String>(), connector.args)
        assertEquals("", connector.userExtra)
    }

    @Test
    fun `bare plugin command parses to Plugin`() {
        val parsed = SlashCommandParser.parse("/plugin:pdf_reader")

        assertTrue(parsed is SlashCommand.Plugin)
        val plugin = parsed as SlashCommand.Plugin
        assertEquals("pdf_reader", plugin.id)
        assertEquals(emptyMap<String, String>(), plugin.args)
        assertEquals("", plugin.userExtra)
    }

    // ═══════════════════════════════════════════════════════════
    // Args + positional user text
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `kv args and positional user text are split correctly`() {
        val parsed = SlashCommandParser.parse("/connector:wechat room=ops 今天值班同学看下")

        assertTrue(parsed is SlashCommand.Connector)
        val connector = parsed as SlashCommand.Connector
        assertEquals("wechat", connector.id)
        assertEquals(mapOf("room" to "ops"), connector.args)
        assertEquals("今天值班同学看下", connector.userExtra)
    }

    @Test
    fun `multiple kv args are collected in insertion order`() {
        val parsed = SlashCommandParser.parse("/connector:github repo=owner/name state=open")

        val connector = parsed as SlashCommand.Connector
        assertEquals("github", connector.id)
        assertEquals(mapOf("repo" to "owner/name", "state" to "open"), connector.args)
        assertEquals("", connector.userExtra)
    }

    @Test
    fun `kv value may contain equals signs`() {
        // Regex captures key=([A-Za-z_][\w.\-]*) and value=(.*) ; the value
        // side is greedy, so "a=b=c" → key=a, value="b=c".
        val parsed = SlashCommandParser.parse("/connector:foo filter=a=b=c")

        val connector = parsed as SlashCommand.Connector
        assertEquals(mapOf("filter" to "a=b=c"), connector.args)
    }

    @Test
    fun `kv parsing stops once positional text begins`() {
        // After the first positional token, subsequent key=value-shaped tokens
        // are treated as user text (CLI-style) rather than dropped into args.
        //（修复：原输入没有 positional token，永远走不到“停止 kv”分支 —— 补一个真 token）
        val parsed = SlashCommandParser.parse("/connector:foo keep=this run drop=that")

        val connector = parsed as SlashCommand.Connector
        assertEquals(mapOf("keep" to "this"), connector.args)
        assertEquals("run drop=that", connector.userExtra)
    }

    @Test
    fun `kv key must start with letter or underscore`() {
        // "1key=val" doesn't match the key regex → positional user text；
        // 且按 CLI 语义 kv 随即停止 —— 后续合法 kv 也归入 user text（原断言与之矛盾）。
        val parsed = SlashCommandParser.parse("/connector:foo 1key=val real=v")

        val connector = parsed as SlashCommand.Connector
        assertEquals(emptyMap<String, String>(), connector.args)
        assertEquals("1key=val real=v", connector.userExtra)
    }

    @Test
    fun `user extra text collapses whitespace runs like sh tokenization`() {
        // 缺陷 7 修复后的 sh-like 词法：空白序列视为单分隔符（引号内除外）。
        //（原测试期望逐字保留旧实现行为，与新词法设计矛盾）
        val parsed = SlashCommandParser.parse("/connector:foo   multiple    spaces   here")

        val connector = parsed as SlashCommand.Connector
        assertEquals("multiple spaces here", connector.userExtra)
    }

    // ═══════════════════════════════════════════════════════════
    // Whitespace tolerance
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `leading whitespace is tolerated`() {
        val parsed = SlashCommandParser.parse("   /connector:ssh")

        assertTrue(parsed is SlashCommand.Connector)
    }

    @Test
    fun `whitespace around the colon is tolerated`() {
        val parsed = SlashCommandParser.parse("/connector : ssh")

        assertTrue(parsed is SlashCommand.Connector)
        assertEquals("ssh", (parsed as SlashCommand.Connector).id)
    }

    // ═══════════════════════════════════════════════════════════
    // Degradation paths
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `input without slash degrades to Unknown`() {
        val parsed = SlashCommandParser.parse("connector:ssh")

        assertTrue(parsed is SlashCommand.Unknown)
        assertEquals("connector:ssh", (parsed as SlashCommand.Unknown).raw)
    }

    @Test
    fun `input without colon degrades to Unknown`() {
        val parsed = SlashCommandParser.parse("/connector ssh")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `empty id degrades to Unknown`() {
        val parsed = SlashCommandParser.parse("/connector:")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `unknown type degrades to Unknown`() {
        val parsed = SlashCommandParser.parse("/skill:code_interpreter")

        assertTrue(parsed is SlashCommand.Unknown)
        assertEquals("/skill:code_interpreter", (parsed as SlashCommand.Unknown).raw)
    }

    @Test
    fun `mcp type is no longer known in theocracy`() {
        // skill / mcp / fusion 域已剥离 —— 旧命令形态降级为 Unknown（直发）。
        val parsed = SlashCommandParser.parse("/mcp:github")

        assertTrue(parsed is SlashCommand.Unknown)
    }

    @Test
    fun `blank input degrades to Unknown`() {
        val parsed = SlashCommandParser.parse("   ")

        assertTrue(parsed is SlashCommand.Unknown)
    }
}
