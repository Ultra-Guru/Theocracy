package com.apex.agent.plugin.market.pkg

import com.apex.agent.plugin.market.format.AgentPluginManifest
import com.apex.agent.plugin.market.format.ManifestValidator

/**
 * .agentpkg 解包结果模型（Spec §3）。
 *
 * 分层契约：
 * - [PluginPackageReader.read] 负责「zip → manifest + entries」；
 * - 失败不抛异常（防御式 IO），全部折叠进 [PackageReadResult.Failure] 的
 *   issue 清单 —— 市场上架/安装向导一次展示全部问题；
 * - 签名验证是**独立第二阶段**（[PluginPackageVerifier.verify]）——
 *   「包结构合法」与「包来源可信」是两个正交判定，市场可以结构入库、
 *   签名后置复核（Ingest Svc 的静态扫描与 Policy 分属不同服务）。
 */

/** 解包成功的包：manifest + 全部条目字节（含 SIGNATURE 文件本体）。 */
class PluginPackage(
    val manifest: AgentPluginManifest,
    /** 包内路径 → 字节（目录条目已剔除，纯文件）。 */
    val entries: Map<String, ByteArray>,
) {
    /** 读文本条目（UTF-8）；路径不存在返回 null。 */
    fun entryText(path: String): String? =
        entries[path]?.toString(Charsets.UTF_8)

    /** 是否含签名文件。 */
    val hasSignatureFile: Boolean get() = ManifestPaths.SIGNATURE in entries
}

/** 包内固定路径（与 ManifestSchema 常量同值，pkg 层自持一份避免跨层反向依赖）。 */
object ManifestPaths {
    const val MANIFEST = "agent-plugin.json"
    const val SIGNATURE = "SIGNATURE"
}

sealed class PackageReadResult {

    /** 结构合法（manifest 解析 + 校验 + 组件存在性全过）。 */
    data class Success(
        val pkg: PluginPackage,
        val warnings: List<ManifestValidator.Issue>,
    ) : PackageReadResult()

    /** 结构不合法（zip 损坏 / manifest 缺失或非法 / 校验 error / 组件缺失）。 */
    data class Failure(val issues: List<ManifestValidator.Issue>) : PackageReadResult()
}

/** 签名验证结果（Spec §8.3：市场分发包必须验签后才入目录）。 */
sealed class PackageVerifyResult {

    /** 验签通过（签名文件合法 + keyring 内 keyId 可信 + 载荷匹配）。 */
    data class Verified(val keyId: String) : PackageVerifyResult()

    /** 无签名文件（市场上架驳回位；本地开发包可放行但必须披露）。 */
    object Unsigned : PackageVerifyResult()

    /** 验签失败（reason 稳定码 + 用户可读信息）。 */
    data class Rejected(val reason: String, val detail: String) : PackageVerifyResult()
}
