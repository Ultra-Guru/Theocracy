<!-- pr: plugin-market-pipeline -->

- 工具执行管线（Spec v1.0 §8，PR-D/5）：统一 Schema 校验 → Capability 判定 → Policy 审批 → Execute → Audit 五段管线 —— 每个拒绝路径都审计（Rejected/Denied/AwaitingApproval/Executed 全落记录）、executor 异常折叠 Denied 不上抛（防御式 IO）
- 风险四档落地（§8.1）：R0 自动 / R1 首次授权后自动 / R2 每次或会话批量确认 / R3 逐次+强提示；未知档位按 R3 保守；R3 会话化请求静默拒绝（逐次是底线）
- 授权模型（§8.2）：安装时（R2+ 默认不授予）/ 运行时（R1 跨会话）/ 会话（rotateSession 失效）三档存储 + revoke 即时生效；Android 权限与 Agent 授权正交 —— 管线零 Android 依赖，只看 capability 声明与授权状态
- 确认流防重放：确认 id 一次性（consumeConfirmation 用后即焚），重放/伪造 → CONFIRMATION_INVALID；确认卡参数与审计同脱敏出口（防双出口不一致窗口）
- 审计脱敏双防线：键名启发（token/secret/password/authorization/apikey/credential/sessionid）+ 值形态启发（JWT 三段 / 40+ hex / Bearer·Basic 前缀 —— 防 token 塞进无害键）；脱敏保留长度标记（<redacted:38>）、非敏感值 200 字符截断
- plugin-ci 增至 9/10（+管线审批与审计）
