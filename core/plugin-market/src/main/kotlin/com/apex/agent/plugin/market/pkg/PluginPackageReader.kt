package com.apex.agent.plugin.market.pkg

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import com.apex.agent.plugin.market.format.ManifestParser
import com.apex.agent.plugin.market.format.ManifestValidator
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * .agentpkg 解包器（Spec §3：zip + manifest 封装）。
 *
 * ## 安全防线（zip-slip）
 *
 * 包是**不可信第三方输入**：解包前对每个 entry 名做穿越检查 ——
 * 绝对路径、反斜杠、驱动器冒号、NUL、`.`/`..` 段一律整包拒绝
 * （不是跳过该 entry —— 半包静默丢弃比拒绝更难排查）。
 *
 * ## 防御式 IO
 *
 * zip 损坏 / IO 异常 / manifest 解析失败全部折叠为
 * [PackageReadResult.Failure]（AppLogger 留痕），不向上抛 ——
 * 与仓库热更链路 SafeZipExtractor 同款纪律，但这里多了组件对账：
 * manifest 声明的组件路径必须在包内真实存在（防「声明了却没装」
 * 的运行期 NPE 转化为安装期明确报错）。
 */
object PluginPackageReader {

    private const val source = "PluginPackageReader"

    /** 单包体积上限（32MB —— 插件是元数据 + 脚本 + 小资源，超此线的包按恶意对待）。 */
    const val MAX_PACKAGE_BYTES = 32L * 1024 * 1024

    /** 单 entry 解压后上限（zip 炸弹防线：16MB 单文件对插件组件足够）。 */
    const val MAX_ENTRY_BYTES = 16L * 1024 * 1024

    /**
     * 从字节读包：解包 → 提取 manifest → 语义校验 → 组件存在性对账。
     * 任何一步失败返回 [PackageReadResult.Failure]（收集该步及之前全部问题）。
     */
    fun read(bytes: ByteArray): PackageReadResult {
        if (bytes.size > MAX_PACKAGE_BYTES) {
            return PackageReadResult.Failure(
                listOf(issue("PKG_TOO_LARGE", "包体积 ${bytes.size} 超过上限 $MAX_PACKAGE_BYTES 字节"))
            )
        }

        // ── 解包（含 zip-slip 检查）─────────────────────────────────
        val entries = try {
            unzipWithGuard(bytes)
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, source,
                "agentpkg 解包失败（折叠为 Failure）: ${e.message}"
            )
            return PackageReadResult.Failure(
                listOf(issue("PKG_CORRUPT", "zip 解包失败: ${e.message ?: e.javaClass.simpleName}"))
            )
        }
        if (entries.isEmpty()) {
            return PackageReadResult.Failure(listOf(issue("PKG_EMPTY", "包内无任何文件")))
        }

        // ── manifest 提取与解析 ─────────────────────────────────────
        val manifestBytes = entries[ManifestPaths.MANIFEST]
            ?: return PackageReadResult.Failure(
                listOf(issue("MANIFEST_MISSING", "包内缺少 ${ManifestPaths.MANIFEST}"))
            )
        val manifest = ManifestParser.parse(manifestBytes.toString(Charsets.UTF_8))
            ?: return PackageReadResult.Failure(
                listOf(issue("MANIFEST_INVALID", "${ManifestPaths.MANIFEST} 无法解析为合法 JSON"))
            )

        // ── 语义校验（收集全部问题，不短路）─────────────────────────
        val validationResult = ManifestValidator.validate(manifest)
        if (!validationResult.isValid) {
            return PackageReadResult.Failure(validationResult.errors)
        }

        // ── 组件存在性对账 ──────────────────────────────────────────
        val presenceIssues = ManifestValidator.validateComponentPresence(manifest, entries.keys)
        if (presenceIssues.isNotEmpty()) {
            return PackageReadResult.Failure(presenceIssues)
        }

        return PackageReadResult.Success(
            PluginPackage(manifest, entries),
            validationResult.warnings,
        )
    }

    // ── 内部 ────────────────────────────────────────────────────────

    /**
     * 解包 + 穿越守卫。返回路径 → 字节（目录条目剔除）。
     * 任一 entry 违规直接抛 [SecurityException]（由调用方折叠为整包拒绝）。
     */
    private fun unzipWithGuard(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                try {
                    requireSafeEntryName(name)
                } catch (e: SecurityException) {
                    // 附上 entry 名再抛 —— 安装失败报告里要指认肇事条目。
                    throw SecurityException("entry \"$name\": ${e.message}")
                }
                if (name.endsWith("/")) continue  // 目录条目：无内容
                val data = zis.readBytes()
                if (data.size > MAX_ENTRY_BYTES) {
                    throw SecurityException(
                        "entry \"$name\" 解压后 ${data.size} 字节，超过单文件上限 $MAX_ENTRY_BYTES"
                    )
                }
                // 同名 entry 覆盖（zip 规范允许多写，后者胜出 —— 与主流解压器一致）
                out[name] = data
                zis.closeEntry()
            }
        }
        return out
    }

    /**
     * entry 名安全检查：防 zip-slip（../ 穿越）、绝对路径、盘符、NUL、反斜杠。
     * public：打包器 / 市场后端 CLI 写包时复用同一守卫（读写两侧同一规则）。
     */
    fun requireSafeEntryName(name: String) {
        if (name.isEmpty()) throw SecurityException("entry 名为空")
        if ('\u0000' in name) throw SecurityException("entry 名含 NUL")
        if (name.startsWith("/")) throw SecurityException("绝对路径")
        if ("\\" in name) throw SecurityException("含反斜杠（zip 路径须正斜杠）")
        if (":" in name) throw SecurityException("含驱动器冒号")
        val segments = name.split('/')
        if (segments.any { it == ".." }) throw SecurityException("含 .. 父段引用")
        if (segments.any { it.isEmpty() && name != "/" } && name.contains("//")) {
            throw SecurityException("含空路径段（双斜杠）")
        }
    }

    private fun issue(code: String, message: String) = ManifestValidator.Issue(
        code = code,
        severity = ManifestValidator.Severity.ERROR,
        path = "package",
        message = message,
    )
}
