package com.apex.agent.plugin.market.pkg

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 包内 SIGNATURE 文件模型（Spec §8.3：.agentpkg 必须签名分发）。
 *
 * ## 文件形态
 *
 * SIGNATURE 是 JSON：
 * - `algorithm`：固定 "ES256"；
 * - `keyId`：市场 keyring 的公钥索引（支持密钥轮换 —— 多 keyId 并存，按 id 取用）；
 * - `signedFiles`：被签文件的逐文件 SHA-256（路径字典序）——
 *   除 SIGNATURE 自身外**包内全部文件**都入清单（icon、README 同样
 *   受保护；篡改任何字节都会破坏 canonical 载荷或触发逐文件复核）；
 * - `signature`：对 [canonicalBytes] 的 ES256 签名（JOSE raw r‖s，base64url 无 padding）。
 *
 * ## canonical 载荷（确定性序列化）
 *
 * 不序列化 JSON 本体（键序/空白不稳），而是对确定文本签名：
 * 逐行 `agentpkg-signature-v1` / `algorithm=…` / `keyId=…` /
 * `file:<path>=<sha256hex>`（signedFiles 按 path 字典序）。
 * 任何实现（市场后端 CLI / 运行时验签 / 第三方工具）按此规则都能
 * 复现同一字节串 —— 跨端互操作的前提。
 */
@Serializable
data class SignatureFile(
    val algorithm: String = "",
    val keyId: String = "",
    val signedFiles: List<SignedFileEntry> = emptyList(),
    /** base64url（无 padding）的 raw r‖s 签名。 */
    val signature: String = "",
) {
    @Serializable
    data class SignedFileEntry(
        val path: String = "",
        @SerialName("sha256") val sha256Hex: String = "",
    )
}

object SignatureCodec {

    private const val source = "SignatureCodec"

    /** canonical 载荷的格式标记行（防降级混签：v2 载荷首行不同即验签失败）。 */
    const val CANONICAL_HEADER = "agentpkg-signature-v1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        encodeDefaults = true
    }

    /** 解析 SIGNATURE 文本；失败折叠 null + 留痕（防御式 IO）。 */
    fun parse(text: String): SignatureFile? = try {
        json.decodeFromString(SignatureFile.serializer(), text.trim())
    } catch (e: Exception) {
        AppLogger.instance.warn(
            LogCategory.PLUGIN, source,
            "SIGNATURE 解析失败（折叠为 null）: ${e.message}"
        )
        null
    }

    /** 序列化（打包器/市场后端写 SIGNATURE 用）。 */
    fun render(f: SignatureFile): String =
        json.encodeToString(SignatureFile.serializer(), f)

    /**
     * 复现 canonical 载荷：对 [sig] 的 algorithm/keyId/signedFiles
     * （按 path 字典序）拼行。**不含 signature 字段本身**（签名不能签自己）。
     */
    fun canonicalBytes(sig: SignatureFile): ByteArray = buildString {
        appendLine(CANONICAL_HEADER)
        appendLine("algorithm=${sig.algorithm}")
        appendLine("keyId=${sig.keyId}")
        sig.signedFiles
            .sortedBy { it.path }
            .forEach { appendLine("file:${it.path}=${it.sha256Hex}") }
    }.toByteArray(Charsets.UTF_8)

    /** 打包侧便捷：对包内文件集（SIGNATURE 除外）生成字典序 digest 清单。 */
    fun digestEntries(entries: Map<String, ByteArray>): List<SignatureFile.SignedFileEntry> =
        entries.keys
            .filter { it != ManifestPaths.SIGNATURE }
            .sorted()
            .map { path ->
                SignatureFile.SignedFileEntry(
                    path = path,
                    sha256Hex = sha256Hex(entries.getValue(path)),
                )
            }

    /** SHA-256 十六进制（小写）。 */
    fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
}
