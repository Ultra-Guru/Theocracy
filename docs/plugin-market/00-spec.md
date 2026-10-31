# Agent Marketplace & Skill/MCP Plugin System — Android Spec v1.0

> 本文档是插件市场系统的**总纲归档**（原始设计输入 2026-10-31）。
> 分篇实现文档见 [01-package-format.md](01-package-format.md) 等；
> 实现进度看 GitHub Project
> [Theocracy Plugin Marketplace](https://github.com/orgs/Ultra-Guru/projects/8)。

## 1. 目标与边界

一句话定义：构建一个 Android 原生 Agent 市场，让用户发现、安装、授权和管理
Agent 插件；插件以 **Skill（SKILL.md 工作流）+ MCP Server（工具/数据连接）
+ Android AppFunctions（本地设备能力）** 三种形态混合存在，并兼容 Claude Code
插件市场格式与 OpenCode 插件入口。

必须做到：

- 插件市场支持搜索、分类、详情页、版本管理、更新、卸载、权限披露。
- Skill 遵循 SKILL.md + YAML frontmatter 开放标准，可被市场扫描、索引、
  按需加载。
- MCP Server 支持远程 HTTP/SSE 与本地 Android AppFunctions 两种来源；
  本地 AppFunctions 以 MCP 工具形式暴露给 Agent。
- 兼容 Claude Code marketplace.json / plugin.json 的元数据字段，市场后端
  可直接摄入此类仓库作为插件源。
- 兼容 OpenCode 插件的发现逻辑：识别 npm 包形式的插件与本地 JS/TS hook
  文件，将其包装为市场可安装条目。
- 所有工具调用走统一 Capability → Policy → Approval → Execute → Audit
  管线，Android 权限不等于 Agent 授权。

明确不做：不实现新的 MCP 传输协议；不替代 Claude Code / OpenCode 客户端；
不在首版做跨设备 Agent 迁移。

## 2. 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Marketplace Frontend (Android)            │
│  发现 / 搜索 / 详情 / 权限清单 / 安装 / 更新 / 评论           │
└──────────────────────────┬──────────────────────────────────┘
                           │ Marketplace API (HTTPS)
┌──────────────────────────▼──────────────────────────────────┐
│                    Marketplace Backend                       │
│  ┌─────────────┐ ┌──────────────┐ ┌──────────────────────┐  │
│  │ Catalog Svc │ │ Ingest Svc   │ │ Policy/Scan Svc      │  │
│  │ 元数据/版本  │ │ Claude/OpenCode│ │ 静态扫描/权限提取    │  │
│  └─────────────┘ └──────────────┘ └──────────────────────┘  │
└──────────────────────────┬──────────────────────────────────┘
                           │ 插件包分发 (signed .agentpkg)
┌──────────────────────────▼──────────────────────────────────┐
│                 Android Agent Runtime (Host)                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Plugin Loader                                          │ │
│  │  ├─ Skill Loader: SKILL.md 解析 + 渐进式披露           │ │
│  │  ├─ MCP Client: 远程 MCP / 本地 AppFunctions MCP       │ │
│  │  └─ Hook Adapter: OpenCode hook 兼容层 (可选启用)      │ │
│  └────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Tool Execution Pipeline                                │ │
│  │  Schema校验 → Capability判定 → Policy审批 → 执行 → 审计 │ │
│  └────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ Local MCP Registry (Android)                           │ │
│  │  已注册 AppFunctions → 以 MCP Tool 形式暴露给 LLM      │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

关键设计决策：Android 端**不内嵌完整 MCP 服务端框架**，而是利用 Android 的
AppFunctions 机制 —— 应用将自己的函数注册为本地 MCP 工具，平台作为中央
工具注册表，Agent 应用以特权 MCP 客户端身份访问。市场层只负责插件元数据、
分发、签名校验和授权 UI。

## 3. 插件包格式：.agentpkg

统一封装 Skill、MCP 配置、Android AppFunctions 声明，本质是 zip + manifest。
完整字段表与签名细节见 [01-package-format.md](01-package-format.md)。

```
my-plugin.agentpkg 内部布局
├── agent-plugin.json          # 市场级清单（必需）
├── skills/                    # Skill 目录（可选，可多个）
│   ├── summarize/SKILL.md
│   └── review/SKILL.md
├── mcp/                       # MCP 配置（可选）
│   ├── servers.json           # 远程 MCP server 声明
│   └── appfunctions.json      # 本地 AppFunctions 绑定声明
├── hooks/                     # OpenCode 兼容 hook（可选）
├── icon.png
├── README.md
└── SIGNATURE                  # ES256 包级签名
```

## 4. Skill 子系统

- **渐进式披露**（三阶段）：
  - Discovery：SKILL.md 的 YAML frontmatter（name + description）——
    市场安装后、Agent 会话启动时；
  - Activation：完整 SKILL.md 正文 —— 用户请求匹配该 skill 描述时；
  - Execution：scripts 与 references —— Skill 指令执行到需要时。
- **完整性校验**：市场分发前为每个 skill manifest 计算 SHA-256，运行时
  加载前校验 digest，不匹配拒绝加载。
- **与 MCP 的关系**：Skill 是工作流层，MCP 是能力层；详情页明确标注
  插件形态（skills-only / mcp-backed / appfunctions-backed）。

## 5. MCP 子系统

两类来源：

| 类型 | 来源 | 传输 | 注册方式 |
|---|---|---|---|
| Remote MCP | 插件声明的外部服务 | HTTPS / SSE | 安装时授权 OAuth 或 API Key |
| Local AppFunctions MCP | Android 应用暴露的 AppFunctions | 本地进程间 | 系统注册表，Agent 特权查询 |

市场侧职责：发现（扫描声明 AppFunctions 的包）→ 元数据提取（生成 MCP Tool
schema）→ 绑定到插件 → 权限映射（每个 AppFunction 标注风险等级）。

**安全约束（强制）**：风险 ≥ R2 的调用必须展示参数摘要并等待确认；
第三方 tool description 视为不可信输入；每次调用记审计日志（脱敏）。

## 6. OpenCode 插件兼容层

Android 市场不运行 Node.js/Bun，提供受限 Hook Adapter（嵌入式 JS 引擎）：
首版兼容 tool.execute.before/after 与 session.created/idle；不支持 tui.*
与 shell.env 注入；脚本无网络/文件系统权限，仅宿主受限 API。

## 7. 市场前端关键页面

插件详情页：头部（图标/名称/开发者/版本/安装量/评分）、形态标签（可多选）、
权限清单（远程 MCP 域名列表 / 本地 AppFunctions 列表含风险等级 /
Skill 脚本执行范围）、Skill 预览（仅 name + description，保护知识产权）、
兼容性（最低 Android API）、安装按钮（R2+ 二次确认）。

搜索索引字段：name、displayName、description、tags、skill descriptions、
tool descriptions；排序因子含安全等级（R0/R1 优先展示）。

## 8. 权限与安全模型

风险等级（与执行策略）：

| 等级 | 含义 | 执行策略 |
|---|---|---|
| R0 | 只读、无敏感数据 | 自动执行 |
| R1 | 读取用户数据但非敏感 | 首次授权后自动 |
| R2 | 写入或修改状态 | 每次确认或会话内批量确认 |
| R3 | 敏感数据或高风险 | 逐次确认 + 强提示 |

安装时展示完整权限清单（R2+ 默认不授予）；运行时 R1 首次请求、R2+ 每次确认卡；
用户可随时撤销。供应链：.agentpkg 必须 ES256 签名，支持密钥轮换，首次上架
人工审核权限声明与 manifest 一致性。

## 9. 与 Claude Code / OpenCode 生态的互通策略

- 作为 Claude Code Marketplace 的「镜像源」：定期抓取公开 marketplace
  仓库摄入，保留原元数据，标注来源；安装仍走 Android 端权限确认。
- 作为 OpenCode 插件的「移动端适配层」：详情页列出实际支持的 hook，
  不支持的标注「移动端不可用」。
- 不伪装 Claude Code 客户端、不调私有 API；不运行未经沙箱隔离的脚本。

## 10. 技术栈与里程碑

技术选型：Kotlin + Compose（客户端）、AndroidX AppFunctions（本地 MCP）、
自研 frontmatter parser + SHA-256（Skill）、QuickJS-Android（受限 JS）、
Go/NestJS（市场后端）、PostgreSQL + Redis（目录/缓存）、Meilisearch（搜索）、
Android Keystore + ES256（签名）。

里程碑：M0 格式定稿 → M1 后端 Ingest → M2 前端 → M3 Skill Loader +
管线 → M4 AppFunctions 绑定 → M5 远程 MCP + R2 确认 → M6 Hook Adapter。

## 本仓实现映射（PR 系列）

| PR | 里程碑覆盖 | 交付 |
|---|---|---|
| PR-A | M0 | .agentpkg 格式 + manifest + ES256 签名（core:plugin-market 基座）|
| PR-B | M3(半) | Skill Loader：渐进式披露 + SHA-256 完整性 |
| PR-C | M4(模型) | MCP 声明 + AppFunctions 本地注册表 + 不可信清洗 |
| PR-D | M3(半)/M5(模型) | 执行管线：风险分级 / Policy 审批 / 审计脱敏 |
| PR-E | M1(客户端侧) | Claude/OpenCode 摄入兼容 + plugin-ci 10 项收齐 |

市场后端（Catalog/Ingest/Policy Svc 的服务端部分）与 Compose 前端为
独立交付物，不在本 PR 系列内 —— 本系列交付 Android 仓库侧的**域模型 +
运行时加载 + 安全管线 + 兼容摄入**（全部纯 JVM 可测）。
