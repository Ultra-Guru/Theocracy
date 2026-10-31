package com.apex.agent.terminalmcp.process

import java.util.concurrent.ConcurrentHashMap

/**
 * T-PROC — 托管进程注册表（terminal 十连 PR 7/10）。
 *
 * ## 语义
 * 长任务（npm run dev / 日志 tail / 编译）派生到**独立会话**（非共享会话），
 * 立即返回 handle；Agent 用 process_read 按游标**非阻塞 poll**（from_cursor
 * 增量读取），用 process_kill 收尸（SIGTERM → 宽限 → SIGKILL 的三级序列由
 * TerminalRuntime.cancel 承担）。退出进程保留可读（Agent 能拿到最终输出与
 * 退出码），直到会话显式关闭时清理。
 *
 * 注册表是 MCP 层的簿记（会话 ↔ 业务名 ↔ 起始游标）；生命周期真相在
 * TerminalRuntime（snapshot SESSIONS）。两侧对账：process_list 合并注册表
 * 与 runtime 状态（已退出/已消失如实上报）。
 */
class ProcessRegistry {

    /** 一条托管进程记录（spawn 时的锚点）。 */
    data class ProcessRecord(
        val sessionId: Long,
        val jobId: Long,
        val name: String,
        val createdAt: Long,
        /** 输出读取起点（run 的 startCursor —— process_read 的默认 from_cursor）。 */
        val startCursor: Long,
    )

    private val records = ConcurrentHashMap<Long, ProcessRecord>() // sessionId → record

    /** 注册（spawn 成功后调用）。 */
    fun register(record: ProcessRecord) {
        records[record.sessionId] = record
    }

    /** 按 handle（session_id）查询。 */
    fun find(sessionId: Long): ProcessRecord? = records[sessionId]

    /** 全量列表（创建时间序）。 */
    fun list(): List<ProcessRecord> = records.values.sortedBy { it.createdAt }

    /** 显式清理（会话关闭/进程被移除时）。 */
    fun remove(sessionId: Long): ProcessRecord? = records.remove(sessionId)

    fun size(): Int = records.size
}
