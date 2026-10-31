package com.apex.agent.plugin.market.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SensitiveParamRedactor] 契约测试（Spec §8.2 审计脱敏）：
 * 键名启发 / 值形态启发 / 截断防线 / 双出口一致性。
 */
class AuditRedactionTest {

    @Test
    fun `sensitive keys are masked by key heuristic`() {
        val out = SensitiveParamRedactor.redact(
            mapOf(
                "api_token" to "tok-abc123",
                "Authorization" to "Bearer xyz",
                "apiKey" to "k-1",
                "user_password" to "hunter2",
                "SESSIONID" to "s-99",
            )
        )
        out.values.forEach {
            assertTrue("敏感键值必须遮蔽: $it", it.startsWith("<redacted:"))
        }
        assertEquals("<redacted:10>", out["api_token"])  // "tok-abc123" = 10 字符
        assertEquals("<redacted:7>", out["user_password"])
    }

    @Test
    fun `credential-shaped values are masked even under innocent keys`() {
        // 值形态防线：攻击者把 token 塞进 query 这类无害键
        val jwt = "eyJhbGciOi.eyJzdWIiOiIx.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV"
        val hex = "a".repeat(64)
        val out = SensitiveParamRedactor.redact(
            mapOf(
                "query" to jwt,
                "etag" to hex,
                "header" to "Bearer abc.def",
                "note" to "普通内容",
            )
        )
        assertTrue(out["query"]!!.startsWith("<redacted:"))
        assertTrue(out["etag"]!!.startsWith("<redacted:"))
        assertTrue(out["header"]!!.startsWith("<redacted:"))
        assertEquals("普通内容", out["note"])
    }

    @Test
    fun `non sensitive values are truncated at budget`() {
        val long = "x".repeat(500)
        val out = SensitiveParamRedactor.redact(mapOf("content" to long))
        assertEquals(SensitiveParamRedactor.MAX_VALUE_CHARS, out["content"]!!.length)
    }

    @Test
    fun `normal values pass through unchanged`() {
        val out = SensitiveParamRedactor.redact(
            mapOf("city" to "上海", "limit" to "10")
        )
        assertEquals("上海", out["city"])
        assertEquals("10", out["limit"])
    }

    @Test
    fun `summary and audit redaction share the same output`() {
        // 双出口一致性：确认卡与审计同脱敏（防确认卡泄密审计不泄的不一致窗口）
        val args = mapOf("token" to "t-1", "q" to "x")
        assertEquals(
            SensitiveParamRedactor.summaryOf(args),
            SensitiveParamRedactor.redact(args),
        )
    }

    @Test
    fun `in memory sink records in order`() {
        val sink = InMemoryAuditSink()
        sink.record(AuditRecord(1, "p", "t", "c", "R0", "Executed", emptyMap(), null, 5))
        sink.record(AuditRecord(2, "p", "t2", "c", "R2", "Denied", mapOf("a" to "<redacted:3>"), "conf-1", null))
        assertEquals(2, sink.all().size)
        assertEquals(1, sink.all()[0].timestampMs)
        assertEquals("conf-1", sink.all()[1].confirmationId)
        assertTrue(sink.all()[1].argsSummary.getValue("a").startsWith("<redacted:"))
    }
}
