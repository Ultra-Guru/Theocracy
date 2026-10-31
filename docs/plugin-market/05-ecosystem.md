# 生态摄入兼容 —— Claude marketplace / OpenCode / Hook 契约

> 实现层文档（PR-E，系列收官）。代码锚点：`core/plugin-market` 的
> `ingest/` 包 + `scripts/check_plugin_docs_sync.py`。
> 总纲见 [00-spec.md](00-spec.md) §6/§9。

## 1. Claude Code marketplace 镜像源（Spec §9.1）

摄入链：marketplace.json → [ClaudeMarketplaceMapper] → `AgentPluginManifest`。

| Claude 字段 | 映射 |
|---|---|
| name | name（slug 校验，非法跳过）+ id 合成 `claude-marketplace.<name>` |
| description / version / license / keywords | 同名保留（version 缺省 0.0.0） |
| author（对象/字符串两形态）| author.name 提取 |
| mcpServers / hooks / skills 存在性 | components 对应字段（包内落点按约定路径） |
| （无风险声明） | **保守合成 R2 capability** —— 源格式缺字段绝不降为自动执行 |
| — | compat.claudeMarketplace = true（来源徽章） |

**镜像源不豁免门禁**：摄入产物必须过本仓 `ManifestValidator`
（测试钉住的不变量）；安装仍走 Android 端权限确认。

## 2. OpenCode 移动端适配层（Spec §6/§9.2）

识别规则（[OpenCodePluginMapper]）：package.json 的 `opencode` 字段
存在且形态合法（非空字符串入口 / 含 plugin 键的对象）→ 摄入；
普通 npm 包（无 opencode 声明）**不摄入**（不伪装适配）。

映射：`opencode-npm.<name>` + compat.opencodePlugin = true +
hooks 细分（对象入口含 hooks 键时合成 R2 capability —— 受限沙箱
执行按写操作级审批）。

## 3. Hook Adapter 兼容契约（Spec §6，M6 引擎接线前先行冻结）

- **支持**：tool.execute.before / tool.execute.after /
  session.created / session.idle；
- **不支持（显式披露）**：tui.* 全系（无 TUI）、shell.env 注入
  （安全边界）—— 详情页渲染「移动端不可用」而非静默失败；
- **沙箱边界**：无网络 / 无文件系统 / 仅宿主桥接 API / 无进程环境变量；
- `HookAdapterContract.evaluate(声明事件清单)` → 支持与不可用分组
  + fullyCompatible 判定。

## 4. 形态标签（Spec §4.3/§7.1）

[PluginShapeBadges] 从 components + compat **严格推导**（不是独立
声明 —— 声明与内容漂移的标签是欺骗面）：

- SKILLS / MCP / APPFUNCTIONS / OPENCODE_HOOK（多选，固定顺序）/
  EMPTY（空壳 —— 上架拦截位）；
- Spec §4.3 三分类便捷判定：skills-only / mcp-backed /
  appfunctions-backed。

## 5. 文档一致性门禁（plugin-ci 10/10 的第二段）

`scripts/check_plugin_docs_sync.py`：docs/plugin-market/
01-package-format.md 字段表 ↔ `ManifestSchema` 常量双向对齐 ——

- docs 字段表出现的每个字段名必须在 schema 常量集合内（文档超前
  或写错即红）；
- 核心必填五字段（schemaVersion/id/name/version/minAndroidApi）
  必须在表（关键字段没写文档等于没发布）。

字段表是插件开发者与市场后端的对接契约 —— Ingest 按文档产
manifest、运行时按代码校验，两头漂移都是真实事故面。

## 6. 测试锚点（plugin-ci 10/10 第一段）

| 覆盖 | 用例 |
|---|---|
| Claude 摄入 | 真实形态样例全量 / 来源徽章 / R2 保守合成 / 纯技能包不合成 / author 双形态 / 非法 slug 跳过 / **摄入产物过 Validator** |
| OpenCode 摄入 | 对象入口 / 字符串入口 / 普通 npm 不摄入 / 坏 JSON 折叠 |
| 形态标签 | 严格推导 / 多形态顺序 / 三分类判定 |
| Hook 契约 | 支持域 / tui 与未知保守不支持 / 兼容报告分组 |
