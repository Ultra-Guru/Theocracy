package com.apex.agent.terminalmcp.event

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T-JOURNAL — JSONL 事件流单测（terminal 十连 PR 6/10）。
 * 临时目录（JVM）零 Android 依赖；轮转/seq 连续性/游标/度量全覆盖。
 */
class EventJournalTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L

    private fun journal(maxBytes: Long = EventJournal.DEFAULT_MAX_FILE_BYTES): EventJournal =
        EventJournal(logDir = tmp.newFolder(), maxFileBytes = maxBytes, clock = { now })

    @Test
    fun `record writes jsonl with seq ts event and fields`() {
        val j = journal()
        j.record("command_proposed", "cmd" to "ls", "session" to 100L)
        j.record("command_executed", "cmd" to "ls", "exit_code" to 0, "truncated" to false)
        val entries = j.tail(10)
        assertEquals(2, entries.size)
        assertEquals("command_proposed", entries[0]["event"]!!.jsonPrimitive.content)
        assertEquals(1L, entries[0]["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals("ls", entries[0]["cmd"]!!.jsonPrimitive.content)
        assertEquals("100", entries[0]["session"]!!.jsonPrimitive.content)
        assertEquals(2L, entries[1]["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals("0", entries[1]["exit_code"]!!.jsonPrimitive.content)
        assertEquals(now, entries[0]["ts"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `seq continues across journal restart`() {
        val dir = tmp.newFolder()
        val first = EventJournal(logDir = dir, clock = { now })
        first.record("tool_call", "tool" to "run")
        first.record("tool_call", "tool" to "run")
        // 重启（新实例同目录）：seq 从尾行恢复
        val second = EventJournal(logDir = dir, clock = { now })
        assertEquals(2L, second.latestSeq())
        second.record("tool_call", "tool" to "run")
        assertEquals(3, second.tail(10).size)
        assertEquals(3L, second.tail(10).last()["seq"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `rotation keeps newest entries and seq continues`() {
        // 每条约 110 字节 —— maxBytes=300 时每文件约 2 条，写 10 条触发多轮转；
        // 双文件上限内只保留最新约 4 条（更旧的被轮转覆盖 —— 预期内，非丢失 bug）。
        val j = journal(maxBytes = 300)
        repeat(10) { j.record("tool_call", "tool" to "run$it", "duration_ms" to it) }
        val entries = j.tail(100)
        assertTrue("retained entries in two-file window: ${entries.size}", entries.size in 2..6)
        // 最新条目必然保留且 seq 连续单调
        val seqs = entries.map { it["seq"]!!.jsonPrimitive.content.toLong() }
        assertEquals(10L, seqs.last())
        assertEquals(seqs, seqs.sorted())
        assertEquals("run9", entries.last()["tool"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tail filters by event type and honors since cursor`() {
        val j = journal()
        repeat(5) { j.record("tool_call", "tool" to "t$it") }
        repeat(3) { j.record("gate_decision", "decision" to "allow") }
        assertEquals(3, j.tail(10, eventType = "gate_decision").size)
        assertEquals(8, j.tail(10).size)
        // since_seq=5 → 仅 seq 6..8（gate_decision 的全部 + 无 tool_call）
        val afterFive = j.tail(10, sinceSeq = 5)
        assertEquals(3, afterFive.size)
        assertTrue(afterFive.all { it["seq"]!!.jsonPrimitive.content.toLong() > 5 })
        // 组合过滤
        assertEquals(3, j.tail(10, eventType = "gate_decision", sinceSeq = 0).size)
        assertEquals(0, j.tail(10, eventType = "tool_call", sinceSeq = 5).size)
    }

    @Test
    fun `tail limit takes the newest slice`() {
        val j = journal()
        repeat(10) { j.record("tool_call", "tool" to "t$it") }
        val tail3 = j.tail(3)
        assertEquals(3, tail3.size)
        assertEquals(8L, tail3.first()["seq"]!!.jsonPrimitive.content.toLong())
        assertEquals(10L, tail3.last()["seq"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `tool metrics computes p50 p95 calls errors over window`() {
        val m = ToolMetrics()
        // 1..100 线性延迟 + 一个离群值：窗口 = 最近 100 条（2..100 + 1000）
        for (i in 1..100) m.record("run", durationMs = i.toLong(), error = i <= 5)
        m.record("run", durationMs = 1000, error = true)
        val snapshot = m.snapshot()["run"]!!
        assertEquals(101, snapshot.calls)
        assertEquals(6, snapshot.errors)
        // 窗口排序后 [2..100, 1000]：p50 秩 49 → 51；p95 秩 94 → 96（离群值在 99 位，
        // 95 分位尚未够到 —— 离群值只影响 p96+，这正是 p95 选型的抗噪价值）
        assertTrue("p50=${snapshot.p50Ms}", snapshot.p50Ms in 50..52)
        assertTrue("p95=${snapshot.p95Ms}", snapshot.p95Ms in 90..100)
    }

    @Test
    fun `tool metrics isolates tools`() {
        val m = ToolMetrics()
        m.record("run", durationMs = 10, error = false)
        m.record("list_sessions", durationMs = 20, error = true)
        assertEquals(1, m.snapshot()["run"]!!.calls)
        assertEquals(1, m.snapshot()["list_sessions"]!!.errors)
    }
}
