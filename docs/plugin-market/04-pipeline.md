# 工具执行管线 —— 风险分级 / Policy 审批 / 审计脱敏

> 实现层文档（PR-D）。代码锚点：`core/plugin-market` 的 `pipeline/` 包。
> 总纲见 [00-spec.md](00-spec.md) §8。

## 1. 五段管线（Spec §2）

```
Schema 校验 → Capability 判定 → Policy 审批 → Execute → Audit
     │              │               │           │         │
 空键 Reject    未声明 Deny    四档判定    异常折叠   全路径留痕
```

调用入口：`ToolCallPipeline.call(ctx, confirmation?)`。

**每个拒绝路径都审计** —— Rejected / Denied / AwaitingApproval /
Executed 全落 `AuditRecord`（审计是调用全生命周期记录，不是成功执行的
副产品）。executor 异常折叠为 `Denied(EXECUTION_FAILED)`，不向上抛
（防御式 IO 纪律）。

## 2. 风险四档（Spec §8.1）

| 档 | 语义 | 策略 | 会话批量 |
|---|---|---|---|
| R0 | 只读无敏感 | 自动执行 | — |
| R1 | 读用户数据 | 首次授权后自动 | — |
| R2 | 写操作 | 每次确认 | ✅（会话内批量）|
| R3 | 敏感/高危 | 逐次确认 + 强提示 | ❌（底线）|

未知档位字符串一律按 **R3 保守**处理。

**Android 权限 ≠ Agent 授权**：管线零 Android 依赖，判定只看 capability
声明 + PermissionPolicy 授权状态 —— 系统权限管「应用能做什么」，Agent
授权管「插件能让 Agent 做什么」，两套体系正交。

## 3. 授权模型（Spec §8.2）

三档存储 + 撤销：

- **安装时授权**（安装向导写入；R2+ 默认不授予 —— 用户显式选择才落）；
- **运行时授权**（R1 首次批准后记录，跨会话保留）；
- **会话授权**（R2 的「本会话不再问」；rotateSession 整体失效；
  R3 的会话化请求静默拒绝）；
- **revoke** 即时生效于后续调用（管线每次判定都过 decide）。

## 4. 确认流（防重放）

```
call(ctx)                    → AwaitingApproval(approval)   [确认卡]
   ↓ 用户在卡上选择
call(ctx, confirmation)      → Executed / Denied(USER_DENIED)
```

- 确认 id **一次性**（consumeConfirmation 用后即焚）—— 重放/伪造 id
  → `Denied(CONFIRMATION_INVALID)`；
- APPROVE_ONCE 仅本次放行；APPROVE_SESSION 落会话授权（仅 R2）；
- R1 的 APPROVE_ONCE 顺带落运行时授权（之后自动）；
- 确认卡参数**已脱敏**（与审计同出口 —— 防确认卡泄密审计不泄的不一致窗口）。

## 5. 审计与脱敏（Spec §5.3/§8.2）

`AuditRecord`：时间 / 插件 id / tool / capability / risk / outcome /
参数摘要（脱敏）/ confirmationId / 耗时。

`SensitiveParamRedactor` 两道防线：

1. **键名启发**：token / secret / password / authorization / apikey /
   credential / sessionid（大小写与驼峰不敏感）→ 值遮蔽；
2. **值形态启发**：JWT 三段式 / 40+ 位 hex / Bearer·Basic 前缀 —— 即使
   键名无害也遮蔽（防攻击者把 token 塞进 `query`）。

脱敏值保留长度一位数字（`<redacted:38>`）：审计可判断「有长凭证」
而不见内容。非敏感值截断 200 字符（审计体积防线）。

## 6. 测试锚点（plugin-ci 9/10）

| 测试类 | 覆盖 |
|---|---|
| PermissionPolicyTest | 四档判定 / 三档存储 / 会话轮换 / 撤销 / 确认一次性 |
| ToolCallPipelineTest | 五段全路径 / 确认流（含重放拒绝）/ 异常折叠 / 每路径一条审计 |
| AuditRedactionTest | 键名启发 / 值形态防线 / 截断 / 双出口一致 / 内存 Sink |
