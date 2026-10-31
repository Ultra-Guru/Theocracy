package com.apex.agent.terminalmcp.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * T-JOURNAL — JSONL 事件流（terminal 十连 PR 6/10）。
 *
 * ## 定位
 * Agent 可调试性的一等公民：命令发起时间、门控决策、执行结果、截断标记
 * 必须可重建，而不是散落在 logcat 里靠 grep 拼凑（termux-mcp 的 events.jsonl
 * 范式）。MCP 面的全部关键动作追加写 JSONL：
 *
 * ```json
 * {"seq":1,"ts":1760000000123,"event":"command_proposed","session":100,"tool":"run","cmd":"ls","risk":"READ_ONLY"}
 * {"seq":2,"ts":1760000000124,"event":"gate_decision","decision":"allow","risk":"READ_ONLY","reason":"..."}
 * {"seq":3,"ts":1760000000987,"event":"command_executed","session":100,"cmd":"ls","exit_code":0,"truncated":false,"duration_ms":861}
 * {"seq":4,"ts":1760000000988,"event":"tool_call","tool":"run","duration_ms":864,"error":false}
 * ```
 *
 * ## 轮转
 * 单文件超 [maxFileBytes]（默认 2MB）→ 现文件改名 `.1`（旧 `.1` 覆盖丢弃，
 * 双文件上限 4MB）。seq 跨轮转单调连续（构造时从最新文件尾行恢复）。
 *
 * ## 度量（termux-mcp /metrics 范式）
 * [ToolMetrics] 内存记录每工具最近 [ToolMetrics.WINDOW] 次调用的延迟与
 * 错误 → p50/p95/调用数/错误率，events 工具的 metrics 字段随行返回。
 *
 * 纯 Kotlin（java.io）—— 目录注入，JVM 单测零 Android 依赖。
 */
class EventJournal(
    private val logDir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val lock = Any()
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(initSeq())

    private val currentFile: File get() = File(logDir, FILE_NAME)
    private val rotatedFile: File get() = File(logDir, "$FILE_NAME.1")

    init {
        if (!logDir.isDirectory) logDir.mkdirs()
    }

    // ─── 写入 ───

    /**
     * 追加一条事件。字段顺序：seq / ts / event 先行（可读性），业务字段随后。
     * 单写者纪律：全部写入经 [lock] 串行（MCP 每连接一线程，量级远不到瓶颈）。
     */
    fun record(type: String, fields: Map<String, JsonElement> = emptyMap()) {
        val entry = kotlinx.serialization.json.buildJsonObject {
            put("seq", kotlinx.serialization.json.JsonPrimitive(seqCounter.incrementAndGet()))
            put("ts", kotlinx.serialization.json.JsonPrimitive(clock()))
            put("event", kotlinx.serialization.json.JsonPrimitive(type))
            fields.forEach { (k, v) -> put(k, v) }
        }
        val line = entry.toString() + "\n"
        synchronized(lock) {
            rotateIfNeeded(line.length)
            currentFile.appendText(line)
        }
    }

    /** 便捷：字符串/数值/布尔字段直写。 */
    fun record(
        type: String,
        vararg fields: Pair<String, Any?>,
    ) = record(type, fields.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is String -> kotlinx.serialization.json.JsonPrimitive(v)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
            is Int -> kotlinx.serialization.json.JsonPrimitive(v)
            is Long -> kotlinx.serialization.json.JsonPrimitive(v)
            is JsonElement -> v
            else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
        }
    })

    // ─── 查询（events 工具）───

    /**
     * 尾部查询：`[sinceSeq, ...]` 之后（不含）的事件中，按 event 过滤取末尾
     * [limit] 条（oldest→newest 序）。跨轮转双文件合并读取。
     */
    fun tail(limit: Int, eventType: String? = null, sinceSeq: Long = 0): List<JsonObject> {
        if (limit <= 0) return emptyList()
        val entries = synchronized(lock) { readAllEntries() }
        return entries.asSequence()
            .filter { seqOf(it) > sinceSeq }
            .filter { eventType == null || eventOf(it) == eventType }
            .toList()
            .takeLast(limit)
    }

    /** 当前最大 seq（调用方翻页游标起点）。 */
    fun latestSeq(): Long = synchronized(lock) { readAllEntries().lastOrNull()?.let { seqOf(it) } ?: 0L }

    // ─── 内部 ───

    private fun rotateIfNeeded(incomingBytes: Int) {
        val f = currentFile
        if (!f.exists() || f.length() + incomingBytes <= maxFileBytes) return
        rotatedFile.delete()
        f.renameTo(rotatedFile)
    }

    private fun readAllEntries(): List<JsonObject> {
        val lines = mutableListOf<String>()
        rotatedFile.takeIf { it.exists() }?.useLines { lines.addAll(it) }
        currentFile.takeIf { it.exists() }?.useLines { lines.addAll(it) }
        return lines.mapNotNull { line ->
            runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
        }
    }

    private fun initSeq(): Long {
        val last = synchronized(lock) { readAllEntries().lastOrNull() }
        return last?.let { seqOf(it) } ?: 0L
    }

    private fun seqOf(entry: JsonObject): Long =
        entry["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    private fun eventOf(entry: JsonObject): String =
        entry["event"]?.jsonPrimitive?.content ?: ""

    companion object {
        const val FILE_NAME = "terminal-events.jsonl"
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}

/**
 * 每工具调用度量（内存环形窗口）：p50/p95 延迟 + 调用数 + 错误数。
 * events 工具的 metrics 字段数据源；重启清零（事件流持久，度量是运行时视图）。
 */
class ToolMetrics(private val window: Int = WINDOW) {

    private val lock = Any()
    private val stats = mutableMapOf<String, WindowStats>()

    /** 记录一次调用。 */
    fun record(tool: String, durationMs: Long, error: Boolean) {
        synchronized(lock) {
            val s = stats.getOrPut(tool) { WindowStats() }
            s.latencies.addLast(durationMs)
            if (s.latencies.size > window) s.latencies.removeFirst()
            s.calls += 1
            if (error) s.errors += 1
        }
    }

    /** 全部工具的度量快照（oldest→newest 无所谓，只做展示）。 */
    fun snapshot(): Map<String, Summary> = synchronized(lock) {
        stats.mapValues { (_, s) ->
            Summary(
                calls = s.calls,
                errors = s.errors,
                p50Ms = percentile(s.latencies, 0.50),
                p95Ms = percentile(s.latencies, 0.95),
            )
        }
    }

    data class Summary(val calls: Int, val errors: Int, val p50Ms: Long, val p95Ms: Long)

    private class WindowStats {
        val latencies = ArrayDeque<Long>()
        var calls = 0
        var errors = 0
    }

    private fun percentile(values: ArrayDeque<Long>, q: Double): Long {
        if (values.isEmpty()) return 0
        val sorted = values.toTypedArray().also { it.sort() }
        // 最近邻秩（业界约定宽松，窗口度量无需插值精度）
        val rank = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.size - 1)
        return sorted[rank]
    }

    companion object {
        /** 延迟窗口：最近 100 次调用（p95 有意义的最小统计面）。 */
        const val WINDOW = 100
    }
}
