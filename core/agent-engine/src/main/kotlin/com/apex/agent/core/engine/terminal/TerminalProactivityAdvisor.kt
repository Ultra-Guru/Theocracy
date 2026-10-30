package com.apex.agent.core.engine.terminal

/**
 * #170 终端主动性顾问 —— 会话编排的轮次级建议注入。
 *
 * 用户反馈：Agent 不会主动用终端 —— 倾向一次性 shell（ShellExecute /
 * TerminalExec），不建 PTY 会话。静态策略文本（EnginePrompts 的
 * Terminal-Use Policy）解决"知道该怎么做"；本顾问解决"在正确的时机被提醒"：
 *
 *  - **SESSION_FLOW**：连续 ≥3 次一次性 shell 完成（滑窗计数；会话流工具
 *    TerminalCreate/run/write/observe/wait 任一出现即清零）→ 注入 system
 *    消息提示模型切换会话流。每命中一次阈值提醒一次（3、6、9…），
 *    streak 停滞不重复唠叨；
 *  - **FAILURE_RECOVERY**：一次性命令连续失败 ≥2 次 → 建议检查输出 / 换会话 /
 *    问用户，不再盲目重试。失败连击同时会作为附注拼进 SESSION_FLOW 建议。
 *
 * （历史：ENSURE_UBUNTU 环境预备建议已随 Ubuntu/PRoot 栈剥离移除。）
 *
 * 引擎接线（零逻辑）：[com.apex.agent.core.engine.ApexAgentEngine] 在
 * executeToolCallStreaming 完成分支调 [onToolCallCompleted]；executeBuildLoop
 * 迭代开始处调 [onIterationStart]，非空建议以 System 消息写入历史（下一轮
 * LLM 请求可见）。纯 Kotlin、可单测（无引擎/Android 依赖）。
 *
 * 线程模型：仅引擎 flow 协程按序调用（工具完成 → 迭代开始严格串行），
 * 无跨线程读写，普通字段即可。
 */
class TerminalProactivityAdvisor {

    /** 建议类别：NONE 仅为语义占位（[onIterationStart] 无建议时返回 null）。 */
    enum class Kind { SESSION_FLOW, FAILURE_RECOVERY, NONE }

    /** 一条可注入的轮次级建议：system 消息文本 + 类别（日志/测试断言用）。 */
    data class Advice(val systemNote: String, val kind: Kind)

    // ═══════════════════════ 状态（会话级，跨 execute() 存续）═══════════════════════

    /** 连续一次性 shell 完成数（滑窗；会话流工具出现即清零）。 */
    private var oneShotStreak = 0

    /** 上次发出 SESSION_FLOW 建议时的 streak 值（再涨 3 才提醒下一次）。 */
    private var lastSessionFlowAdviceStreak = 0

    /** 一次性命令连续失败数（任一次成功即清零）。 */
    private var consecutiveOneShotFailures = 0

    // ═══════════════════════ 工具完成钩子（引擎：executeToolCallStreaming）═══════════════════════

    /**
     * 引擎工具完成钩子。
     *
     * @param toolId 注册表 id（引擎已把 provider 名映射回 registry id）
     * @param succeeded 工具成败（流式 Error 信号或文本前缀判定，与
     *   ToolCallComplete.success 同源）
     * @param args 原始参数 JSON（默认空 —— 关键词检测的次要信号）
     */
    fun onToolCallCompleted(toolId: String, succeeded: Boolean, args: String = "") {
        when {
            toolId in ONE_SHOT_TOOLS -> {
                oneShotStreak++
                if (succeeded) {
                    consecutiveOneShotFailures = 0
                } else {
                    consecutiveOneShotFailures++
                }
            }
            toolId in SESSION_FLOW_TOOLS -> {
                // 模型已在用会话流 —— 一次性 streak 失去意义，清零。
                oneShotStreak = 0
            }
        }
    }

    // ═══════════════════════ 迭代开始钩子（引擎：executeBuildLoop）═══════════════════════

    /**
     * 迭代开始：判定本轮是否注入建议。返回 null = 无建议。
     *
     * 优先级 SESSION_FLOW > FAILURE_RECOVERY；失败连击作为附注拼进前两者。
     */
    fun onIterationStart(userPrompt: String): Advice? {
        val failureNote = if (consecutiveOneShotFailures >= FAILURE_STREAK_THRESHOLD) {
            " Note: the last $consecutiveOneShotFailures one-shot command(s) also failed — " +
                "inspect their output before continuing."
        } else ""

        // ── SESSION_FLOW：连续一次性 shell 且距上次提醒又攒满一个阈值 ──
        if (oneShotStreak >= SESSION_FLOW_THRESHOLD &&
            oneShotStreak >= lastSessionFlowAdviceStreak + SESSION_FLOW_THRESHOLD
        ) {
            lastSessionFlowAdviceStreak = oneShotStreak
            return Advice(
                // 实录 #10：旧的 "STOP issuing further one-shot commands" 措辞过于
                // 绝对 —— 模型正在用一次性命令推进正常工作（快速探测/短命令），
                // 被强制切到会话流后反而掉进 heredoc 挂死坑。改为建议式：会话流
                // 适用于多步/长命令；简单短探测可继续；同时把 heredoc 禁忌与
                // PS2 挂死自救写进提醒（实录 #7 的事故路径）。
                systemNote = "Terminal guidance: you have now run $oneShotStreak consecutive " +
                    "one-shot shell commands (ShellExecute / TerminalExec). For multi-step or " +
                    "long-running work, switch to a session flow: TerminalCreate → TerminalRun " +
                    "(run long commands in the background) → TerminalObserve / TerminalWait — " +
                    "sessions preserve cwd, environment and state across commands. Simple " +
                    "one-liner probes may still use TerminalExec. NEVER paste multi-line " +
                    "heredoc scripts (cmd <<'EOF' ... EOF) into a session — write the script " +
                    "to a file first (CodeWrite / Write), then execute the file; if a session " +
                    "ever hangs at a '>' (PS2) continuation prompt, recover with TerminalWrite " +
                    "{\"kind\":\"KEY\",\"key\":\"CTRL_C\"}.$failureNote",
                kind = Kind.SESSION_FLOW
            )
        }

        // ── FAILURE_RECOVERY：一次性命令连击失败（无更高优先级建议时独立成条）──
        if (consecutiveOneShotFailures >= FAILURE_STREAK_THRESHOLD) {
            return Advice(
                systemNote = "Terminal guidance: the last $consecutiveOneShotFailures one-shot " +
                    "shell commands failed. Do NOT re-run the identical command again — " +
                    "inspect the output above, open a terminal session (TerminalCreate + " +
                    "TerminalRun), or ask the user how to proceed. If a session is hung at a " +
                    "> '>' (PS2) prompt (unterminated heredoc), interrupt it with TerminalWrite " +
                    "{\"kind\":\"KEY\",\"key\":\"CTRL_C\"} — JOB-scope TerminalSignal cannot " +
                    "reach the shell while it is the foreground process.",
                kind = Kind.FAILURE_RECOVERY
            )
        }

        return null
    }

    companion object {
        /** 一次性（无会话）shell 工具：streak 计数与失败追踪的对象。 */
        val ONE_SHOT_TOOLS = setOf("ShellExecute", "TerminalExec")

        /** 会话流工具：任一出现即证明模型已切换会话编排，streak 清零。 */
        val SESSION_FLOW_TOOLS = setOf(
            "TerminalCreate", "TerminalRun", "TerminalWrite",
            "TerminalObserve", "TerminalWait"
        )

        /** SESSION_FLOW 触发阈值：连续一次性 shell 完成数。 */
        const val SESSION_FLOW_THRESHOLD = 3

        /** 失败连击提示阈值。 */
        const val FAILURE_STREAK_THRESHOLD = 2
    }
}
