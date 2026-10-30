package com.apex.agent.di

import com.apex.agent.platform.terminal.pty.JniNativePty
import com.apex.agent.platform.terminal.pty.NativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicy
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.compat.LegacyTerminalManager
import com.apex.agent.platform.terminal.runtime.ExecutionBackendRegistry
import com.apex.agent.platform.terminal.runtime.LocalShellBackend
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.persistence.SessionMetadataStore
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import android.content.Context
import javax.inject.Singleton

/**
 * Theocracy 终端接线 —— 只保留 Android 本地 Shell（local backend）。
 *
 * Ubuntu/PRoot 整栈（rootfs provisioner / proot backend / apt 包管理器 /
 * Linux 环境健康 / apexctl 桥 / guest 文件系统）已随终端收束整体移除；
 * 后续若重建 Linux 侧能力，从 [ExecutionBackendRegistry] 扩展新后端即可，
 * 会话/PTY/job/观察等核心层不变。
 */
@Module
@InstallIn(SingletonComponent::class)
object TerminalModule {

    /** Bind the [NativePty] interface to the JNI-backed production adapter (Spec §2.2/§44.1). */
    @Provides
    @Singleton
    fun provideNativePty(): NativePty = JniNativePty()

    /**
     * 终端设置共享单例（设置中心 v2 扩展）：设置页「终端」大分类与终端页
     * 必须看到同一份内存态 —— store 提升为 Hilt 单例后，任意一侧改动经
     * 同一 StateFlow 即时广播到另一侧（此前各 VM 私建实例，prefs 落盘虽
     * 共享但内存态互不可见，改动要等 VM 重建才生效）。
     *
     * 两个 store 同绑 "apex_terminal" prefs（与 [provideTerminalPolicy] 的
     * 动态策略读同一文件），无新存储面。配色方案单例已随旧交互终端退役
     * （T96 终端大重构删除 scheme 子包），不再提供。
     */
    @Provides
    @Singleton
    fun provideTerminalSettingsStore(@ApplicationContext context: Context): com.apex.agent.ui.screen.terminal.TerminalSettingsStore =
        com.apex.agent.ui.screen.terminal.TerminalSettingsStore(context)

    @Provides
    @Singleton
    fun provideCommandPolicyStore(@ApplicationContext context: Context): com.apex.agent.ui.screen.terminal.CommandPolicyStore =
        com.apex.agent.ui.screen.terminal.CommandPolicyStore(context)


    /**
     * 终端策略接线（设置页黑白名单 → LINE 写入策略层）。
     *
     * TerminalViewModel 的设置页把用户黑白名单写入 "apex_terminal" prefs
     * （键 cmd_blacklist / cmd_whitelist，值 = 命令头 token 的小写集合）。
     * 此前该配置**从未被任何执行路径消费**（设置页是纯装饰）—— 这里通过
     * [TerminalPolicyImpl] 的 dynamicPolicy 每次检查时实时读取 prefs 接入：
     *
     * - 用户名单为空 → 默认 [CommandPolicy]（DEFAULT_DENYLIST + ALLOW_ALL）
     * - 有黑名单 → 黑名单 **并集** 内置默认危险命令（shutdown/reboot/mkfs/dd/
     *   halt/poweroff 仍被拦截，用户自定义名单是叠加而非替换）
     * - 有白名单（非空）→ ALLOWLIST_ONLY：仅白名单内的命令头允许；复杂命令
     *   （管道/&&/sh -c 等）按 Spec §6 保守拒绝
     *
     * 用户在设置页增删条目后无需重启 —— dynamicPolicy 每次检查重新读 prefs。
     */
    @Provides
    @Singleton
    fun provideTerminalPolicy(@ApplicationContext context: Context): TerminalPolicy {
        val prefs = context.getSharedPreferences("apex_terminal", Context.MODE_PRIVATE)
        return TerminalPolicyImpl(
            dynamicPolicy = {
                val blacklist = prefs.getStringSet("cmd_blacklist", emptySet()).orEmpty()
                val whitelist = prefs.getStringSet("cmd_whitelist", emptySet()).orEmpty()
                if (blacklist.isEmpty() && whitelist.isEmpty()) {
                    null // 未配置 → 构造期默认 CommandPolicy()
                } else {
                    com.apex.agent.platform.terminal.policy.CommandPolicy(
                        mode = if (whitelist.isNotEmpty())
                            com.apex.agent.platform.terminal.policy.CommandPolicyMode.ALLOWLIST_ONLY
                        else
                            com.apex.agent.platform.terminal.policy.CommandPolicyMode.ALLOW_ALL,
                        allowlist = whitelist,
                        // 并集默认危险命令：用户的黑名单叠加内置名单（更安全），
                        // 用户没配置时这里不会到达（null 分支已回退默认）。
                        denylist = com.apex.agent.platform.terminal.policy.DefaultCommandPolicy.DEFAULT_DENYLIST + blacklist
                    )
                }
            }
        )
    }

    /** Persistence store for crash recovery (Spec §39). Stores session JSON in app files dir. */
    @Provides
    @Singleton
    fun provideSessionMetadataStore(@ApplicationContext context: Context): SessionMetadataStore =
        SessionMetadataStore(java.io.File(context.filesDir, "atr-sessions"))

    /** 后端注册表：Theocracy 只有 local（Android 本地 Shell，golden 行为）。 */
    @Provides
    @Singleton
    fun provideExecutionBackendRegistry(): ExecutionBackendRegistry =
        ExecutionBackendRegistry.of(LocalShellBackend())

    /** TerminalRuntime —— create(backendId=…) 统一经后端注册表路由（当前仅 local）。
     *  T91（D2-D4）：创建即安装进 TerminalRuntimeRegistry —— TerminalService
     *  （specialUse 前台服务）由此取得同一单例；AIDL 消费路径见 TerminalServiceClient。 */
    @Provides
    @Singleton
    fun provideTerminalRuntime(
        @ApplicationContext context: android.content.Context,
        native: NativePty,
        policy: TerminalPolicy,
        store: SessionMetadataStore,
        backends: ExecutionBackendRegistry
    ): TerminalRuntime {
        // #F-⑰：native VT 引擎黑匣子接线（幂等）—— 上一次未干净收尾的 feed
        // trail 在此归因（logcat + .crash 留档）；必须在首个引擎创建前调用。
        com.apex.agent.vtnative.VtFeedTrail.install(java.io.File(context.filesDir, "logs"))
        return TerminalRuntimeImpl(
            native, policy,
            backendRegistry = backends,
            persistenceStore = store,
            // T82：Shell Marker Protocol —— 每个前台 job 携带 OSC 633 marker（真实
            // 退出码 + jobId）；prompt 启发式降级为 fallback。生产默认开启。
            enableShellMarkers = true
        ).also {
            // T91 (D2–D4)：AIDL 终端服务注册 —— 首个 Runtime 创建后立即可被
            // 外部进程绑定（Operit 对齐）；与 #F-⑰ 前置黑匣子接线共同生效。
            com.apex.agent.platform.terminal.service.TerminalRuntimeRegistry.install(it)
        }
    }

    /** Compat facade: old TerminalManager API → new Runtime (settle-time DELETED). Spec §35. */
    @Provides
    @Singleton
    fun provideLegacyTerminalManager(runtime: TerminalRuntime): LegacyTerminalManager =
        LegacyTerminalManager(runtime)
}
