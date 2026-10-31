package com.apex.agent.plugin.market.pkg

import com.apex.agent.plugin.market.format.ManifestSchema

/**
 * 包级验签门面（Spec §8.3：市场分发的 .agentpkg 必须验签后才入目录）。
 *
 * ## 校验链（顺序即安全语义）
 *
 * 1. SIGNATURE 文件存在性与可解析性；
 * 2. 算法必须 ES256、keyId 非空；
 * 3. canonical 载荷验签（[Es256Verifier] + 市场 keyring）；
 * 4. **逐文件复核**：signedFiles 的每个 digest 必须与包内对应文件一致，
 *    且包内文件（SIGNATURE 自身除外）必须全部入清单 ——
 *    「漏签文件」视作篡改（防攻击者往已签名包里塞未受保护的私货）。
 *
 * 第 3 步通过但第 4 步失败 = 签名本体有效而内容被换 ——
 * 与第 3 步直接失败同等拒绝，但 reason 不同（审计可区分攻击形态）。
 */
object PluginPackageVerifier {

    /** 验签拒绝原因码（稳定值，市场后台聚合统计用）。 */
    const val REASON_SIGNATURE_MISSING = "SIGNATURE_MISSING"
    const val REASON_SIGNATURE_INVALID = "SIGNATURE_INVALID"
    const val REASON_ALGORITHM = "ALGORITHM_UNSUPPORTED"
    const val REASON_KEY_ID = "KEY_ID_MISSING"
    const val REASON_CRYPTO = "SIGNATURE_CRYPTO_FAILED"
    const val REASON_DIGEST_MISMATCH = "DIGEST_MISMATCH"
    const val REASON_FILE_COVERAGE = "FILE_COVERAGE_INCOMPLETE"

    /**
     * 验签。前提：[pkg] 已过 [PluginPackageReader.read] 的结构校验
     * （结构不合法的包谈签名没有意义）。
     */
    fun verify(pkg: PluginPackage, keyring: Map<String, String>): PackageVerifyResult {
        val sigBytes = pkg.entries[ManifestPaths.SIGNATURE]
            ?: return PackageVerifyResult.Rejected(
                REASON_SIGNATURE_MISSING,
                "包内缺少 ${ManifestPaths.SIGNATURE} —— 市场分发包必须签名（Spec §8.3）"
            )
        val sig = SignatureCodec.parse(sigBytes.toString(Charsets.UTF_8))
            ?: return PackageVerifyResult.Rejected(
                REASON_SIGNATURE_INVALID, "SIGNATURE 文件无法解析为合法 JSON"
            )
        if (sig.algorithm != ManifestSchema.SIGNATURE_ALGORITHM) {
            return PackageVerifyResult.Rejected(
                REASON_ALGORITHM,
                "签名算法须为 ${ManifestSchema.SIGNATURE_ALGORITHM}，当前 \"${sig.algorithm}\""
            )
        }
        if (sig.keyId.isEmpty()) {
            return PackageVerifyResult.Rejected(REASON_KEY_ID, "keyId 为空")
        }

        // ── canonical 验签 ──────────────────────────────────────────
        when (Es256Verifier.verify(SignatureCodec.canonicalBytes(sig), sig.signature, sig.keyId, keyring)) {
            Es256Verifier.VerifyOutcome.VERIFIED -> Unit  // 继续逐文件复核
            Es256Verifier.VerifyOutcome.UNKNOWN_KEY ->
                return PackageVerifyResult.Rejected(
                    REASON_CRYPTO, "keyring 中无 keyId=\"${sig.keyId}\" —— 未知发布者"
                )
            Es256Verifier.VerifyOutcome.BAD_KEY ->
                return PackageVerifyResult.Rejected(REASON_CRYPTO, "keyring 公钥损坏（无法构造 EC 公钥）")
            Es256Verifier.VerifyOutcome.BAD_SIGNATURE ->
                return PackageVerifyResult.Rejected(REASON_CRYPTO, "签名编码非法（非 64 字节 raw r‖s）")
            Es256Verifier.VerifyOutcome.MISMATCH ->
                return PackageVerifyResult.Rejected(REASON_CRYPTO, "验签失败 —— 载荷或签名被篡改")
        }

        // ── 逐文件复核 ─────────────────────────────────────────────
        for (entry in sig.signedFiles) {
            val bytes = pkg.entries[entry.path]
            if (bytes == null) {
                return PackageVerifyResult.Rejected(
                    REASON_DIGEST_MISMATCH, "清单声明文件 \"${entry.path}\" 不在包内"
                )
            }
            val actual = SignatureCodec.sha256Hex(bytes)
            if (actual != entry.sha256Hex) {
                return PackageVerifyResult.Rejected(
                    REASON_DIGEST_MISMATCH,
                    "文件 \"${entry.path}\" digest 不匹配（声明 ${entry.sha256Hex}，实际 $actual）"
                )
            }
        }

        // ── 覆盖完整性（漏签文件 = 篡改面）──────────────────────────
        val covered = sig.signedFiles.map { it.path }.toSet()
        val uncovered = pkg.entries.keys
            .filter { it != ManifestPaths.SIGNATURE && it !in covered }
            .sorted()
        if (uncovered.isNotEmpty()) {
            return PackageVerifyResult.Rejected(
                REASON_FILE_COVERAGE,
                "包内文件未入签名清单（不受保护）: ${uncovered.joinToString(", ") { "\"$it\"" }}"
            )
        }

        return PackageVerifyResult.Verified(sig.keyId)
    }
}
