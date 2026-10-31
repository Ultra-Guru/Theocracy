package com.apex.agent.plugin.market.pipeline

/**
 * 风险等级（Spec §8.1）—— 执行策略的唯一依据。
 *
 * **Android 权限 ≠ Agent 授权**：本层不读 Android 权限系统，只看插件
 * 声明的 capability 风险档位与用户的 Agent 授权状态 —— 两套授权体系
 * 正交（系统权限管「应用能做什么」，Agent 授权管「插件能让 Agent
 * 做什么」）。
 */
enum class RiskLevel(
    /** 与 manifest.capabilities[].risk / appfunctions.json 同域字符串。 */
    val code: String,
    /** 用户可读档位名（权限清单与确认卡展示）。 */
    val label: String,
) {
    /** 只读、无敏感数据 —— 自动执行（读取公开信息、本地计算）。 */
    R0("R0", "只读"),

    /** 读取用户数据但非敏感 —— 首次授权后自动（脱敏读日历/读通知）。 */
    R1("R1", "读用户数据"),

    /** 写入或修改状态 —— 每次确认或会话内批量确认（创建事件/发消息/改文件）。 */
    R2("R2", "写操作"),

    /** 敏感数据或高风险 —— 逐次确认 + 强提示（位置/联系人/支付相关）。 */
    R3("R3", "敏感");

    /** 档位序（越高越危险；比较用）。 */
    val rank: Int get() = ordinal

    /** 是否需要用户确认（R2 会话批量可豁免单次；R3 永不豁免）。 */
    val requiresConfirmation: Boolean get() = this >= R2

    /** 是否允许会话内批量确认（Spec §8.1：仅 R2；R3 必须逐次）。 */
    val allowsSessionGrant: Boolean get() = this == R2

    companion object {
        /** 解析档位（未知值 → null，调用方决定拒绝或默认 R3 保守）。 */
        fun of(code: String): RiskLevel? = entries.firstOrNull { it.code == code }
    }
}
