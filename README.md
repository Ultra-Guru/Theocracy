<a id="readme-top"></a>
<div align="center">

# Theocracy

**An autonomous AI agent that lives entirely on your Android device.**

纯 Android 端自主智能体：OpenAI 兼容流式 LLM 接入、多模式思考引擎、
真实终端（PRoot Ubuntu 24.04 + 本地宿主 Shell）、Root/Shizuku/无障碍三级权限链、
Theocracy Flow 流水渲染 —— 全部跑在一台手机上，无需任何服务器。

**CI · 质量门禁**

<a href="https://github.com/Ultra-Guru/Theocracy/actions/workflows/ci.yml"><img src="https://github.com/Ultra-Guru/Theocracy/actions/workflows/ci.yml/badge.svg?branch=main" alt="CI"/></a>
<a href="https://github.com/Ultra-Guru/Theocracy/actions/workflows/quality-gate.yml"><img src="https://github.com/Ultra-Guru/Theocracy/actions/workflows/quality-gate.yml/badge.svg?branch=main" alt="Quality Gate"/></a>
<img src="https://img.shields.io/badge/Gradle-8.10-02303A?logo=gradle&logoColor=white" alt="Gradle"/>
<img src="https://img.shields.io/badge/JDK-17-orange?logo=openjdk&logoColor=white" alt="JDK 17"/>
<img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin"/>

</div>

---

## 这是什么

Theocracy 是从 [Android-Guru-Agent](https://github.com/Ultra-Guru/Android-Guru-Agent)
拆分而生的 **Agent 模式独立软件**：原软件中 Agent 模式与 Coding 模式并行，
维护成本随双模式耦合上升；Theocracy 承载完整的 Agent 能力，成为
「设备上的自主智能体」单一定位的产品。

原仓库继续作为 **Coding 模式专属软件** 演进（只保留 Ubuntu 环境）；
Theocracy 保留双环境执行（PRoot Ubuntu + 本地宿主终端）。

## 核心能力

### 🧠 思考引擎（完整继承 + 演进）
- 八档思考深度（AUTO 自适应 → ULTRACODE 深潜）
- 多执行模式：CHAT / AGENT / BUILD / PLAN / GOAL / LOOP / SPEC / REFLECTION / HUMAN_ASSIST
- 上下文压缩（三层混合 + 归档检索）、思考逻辑与提示词工程全量保留

### 🌊 Theocracy Flow —— 流水渲染 v2
流式输出视觉体系重做（Rikka 系干净 MD3 基底 + 四项自主创新）：
- **节奏感知光标**：光标高度/亮度随 token 流速自适应 —— 疾速吐字挺拔恒亮，深思组织收窄慢呼吸
- **段落凝实**：定稿段落 0.55 → 1 一次性淡入，「墨水凝实」隐喻
- **代码围栏即时终端化**：流式中闭合代码块即刻进入终端卡形态，不等完成态
- **流速丝带**：气泡底部渐变条宽度映射回复体量，纯布局驱动零动画成本

### 📟 Terminal（原 AndroidShell，更名 + 演进）
- **真实 PTY 执行**：PRoot 沙箱化 Ubuntu 24.04（apt / git / python / node…）
- **本地宿主 Shell**：mksh on toybox，零安装即用
- M³ 控制台视觉 × 多会话 / 环境中心 / 命令历史 / 依赖安装
- 环境与工作区管理（LinuxWorkspaceManager）

### 🔐 三级权限链
Root → Shizuku（ADB 级）→ 无障碍服务，能力递降兜底

### 🔌 更多
- OpenAI 兼容多 Provider 接入（流式）
- GitHub 自主协作（9 个 github_* 工具）
- 浏览器代理（布局采集 / 视觉画像）
- 通知 / 短信 / 应用控制等设备级工具
- 插件化 SDK

## 刻意不为（重新设计中）

以下域在拆分时**有意剥离**，将在 Theocracy 上重新设计后回归：
- **Market（市场）** —— 工具/技能分发体系
- **Skills（技能）** —— 渐进披露与装备链
- **MCP** —— Model Context Protocol 客户端
- **记忆系统** —— 情景记忆 / 知识图谱 / 经验整理

## 快速开始

```bash
git clone https://github.com/Ultra-Guru/Theocracy.git
cd Theocracy
./gradlew :app:assembleDebug   # JDK 17+
```

或直接从 [Releases](https://github.com/Ultra-Guru/Theocracy/releases) 下载 APK。

首次启动：设置 → 模型 → 添加 OpenAI 兼容 Provider（BaseURL + API Key）→ 回到对话页开始。

## 构建

| 项 | 值 |
|---|---|
| 最低 Android 版本 | 见 `app/build.gradle.kts` `minSdk` |
| 工具链 | JDK 17 · Gradle 8.10 · Kotlin 2.2 · AGP 8 |
| 验证 | GitHub Actions（CI + Quality Gate + Guard Rails） |

```bash
./gradlew :app:assembleDebug      # Debug APK
./gradlew :core:agent-engine:test # 引擎 JVM 单测
```

## 项目结构

```
app/                    # Android 应用（UI / DI / 服务 / 工具宿主）
core/
  agent-engine/         # 思考引擎（模式 / 压缩 / 提示词 / 工具编排）
  llm-adapter/          # OpenAI 兼容流式客户端
  tool-registry/        # 工具注册表与执行器
  logging/              # 结构化日志
platform/
  terminal/             # PRoot Ubuntu + 本地宿主 Shell（proot-binaries / rootfs）
  browser-agent/        # 浏览器代理
  privilege/            # Root / Shizuku / 无障碍
  persistence/          # 持久化基座
terminal-emulator/      # VT 终端仿真（无 Compose 依赖）
terminal-view/          # 终端渲染视图
terminal-native/        # native 层
vendor/backdrop/        # 玻璃材质库
plugin-sdk/             # 插件 SDK
```

## 从 Android-Guru-Agent 迁移

Theocracy 的 git 历史完整保留了原仓库脉络（初始提交即剥离点）。
对话历史 / 设置等本地数据与原软件不互通（应用Id独立）。

## 贡献

- 提交信息与更新日志遵循 `.changelog/` 片段流程（见 CI 门禁）
- PR 需过 CI + Quality Gate + Guard Rails

## 许可

同原仓库（见 [LICENSE](LICENSE)）。

---

<div align="center">

**Theocracy** — the agent is the device.

</div>
