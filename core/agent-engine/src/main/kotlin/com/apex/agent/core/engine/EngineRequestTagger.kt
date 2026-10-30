package com.apex.agent.core.engine

import com.apex.agent.core.llm.runtime.LlmRequestContext

/**
 * 请求上下文组装件（自原 EngineSkillPromptResolver 迁出 —— Theocracy
 * 剥离 skill 域后仅保留通用的 executionTags 通道）：
 *
 * T76 — executionTags（taskId/stepId）填入 LlmRequestContext；未接线时原样返回。
 * 引擎内部所有 LlmRequestContext 构造点经 [tagged] 包裹后携带任务/步骤关联，
 * 诊断日志可按任务聚合。
 */
internal fun ApexAgentEngine.tagged(ctx: LlmRequestContext): LlmRequestContext {
    val tags = executionTags ?: return ctx
    return ctx.copy(taskId = tags.first, stepId = tags.second)
}
