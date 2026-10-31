package com.apex.agent.plugin.market.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ES256 签名 round-trip 契约测试：签名/验签正反例、篡改检测、
 * keyring 信任模型（未知 keyId / 坏 key / 坏编码）、DER↔raw 转换边界。
 *
 * 依据 Spec §8.3：篡改 manifest 任一字节 → 验签失败。
 */
class Es256SignatureRoundTripTest {

    private val keyPair = Es256Verifier.generateKeyPair()
    private val keyId = "test-key"
    private val keyring = mapOf(keyId to Es256Verifier.publicKeyBase64(keyPair))

    private fun signedPackage(): Pair<ByteArray, Map<String, String>> {
        val builder = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .file("skills/summarize/SKILL.md", "# skill")
            .file("mcp/servers.json", "{}")
            .file("README.md", "doc")
            .sign(keyPair, keyId)
        return builder.build() to keyring
    }

    private fun verify(bytes: ByteArray, ring: Map<String, String>): PackageVerifyResult {
        val pkg = (PluginPackageReader.read(bytes) as PackageReadResult.Success).pkg
        return PluginPackageVerifier.verify(pkg, ring)
    }

    // ── 正例 ────────────────────────────────────────────────────────

    @Test
    fun `honest signed package verifies`() {
        val (bytes, ring) = signedPackage()
        val result = verify(bytes, ring)
        assertTrue("诚实签名包必须验签通过: $result", result is PackageVerifyResult.Verified)
        assertEquals(keyId, (result as PackageVerifyResult.Verified).keyId)
    }

    @Test
    fun `canonical bytes are deterministic`() {
        // 同一签名文件两次复现 canonical 载荷，字节必须一致（跨端互操作前提）
        val sig = SignatureFile(
            algorithm = "ES256", keyId = "k",
            signedFiles = listOf(
                SignatureFile.SignedFileEntry("b.txt", "02"),
                SignatureFile.SignedFileEntry("a.txt", "01"),
            ),
        )
        val once = SignatureCodec.canonicalBytes(sig)
        val twice = SignatureCodec.canonicalBytes(sig)
        assertTrue(once.contentEquals(twice))
        // 字典序：a 在 b 前
        val text = once.toString(Charsets.UTF_8)
        assertTrue(text.indexOf("file:a.txt") < text.indexOf("file:b.txt"))
        assertTrue(text.startsWith(SignatureCodec.CANONICAL_HEADER + "\n"))
    }

    // ── 篡改检测（Spec：任一字节篡改 → 失败）───────────────────────

    @Test
    fun `tampered manifest byte is rejected`() {
        val (bytes, ring) = signedPackage()
        val tampered = replaceEntry(bytes, ManifestPaths.MANIFEST) { raw ->
            // version "1.2.0" → "1.2.1"：单字符篡改
            String(raw, Charsets.UTF_8).replace("1.2.0", "1.2.1").toByteArray(Charsets.UTF_8)
        }
        val result = verify(tampered, ring)
        assertTrue("篡改 manifest 必须被拒: $result", result is PackageVerifyResult.Rejected)
        val rejected = result as PackageVerifyResult.Rejected
        // digest 复核与 canonical 验签都会撞上 —— 两者皆可接受，但 reason 必须是二者之一
        assertTrue(
            rejected.reason in setOf(PluginPackageVerifier.REASON_DIGEST_MISMATCH, PluginPackageVerifier.REASON_CRYPTO)
        )
    }

    @Test
    fun `tampered content file is rejected by digest check`() {
        val (bytes, ring) = signedPackage()
        val tampered = replaceEntry(bytes, "README.md") { "被篡改的内容".toByteArray(Charsets.UTF_8) }
        val result = verify(tampered, ring) as PackageVerifyResult.Rejected
        assertEquals(PluginPackageVerifier.REASON_DIGEST_MISMATCH, result.reason)
    }

    @Test
    fun `file outside signature manifest is rejected`() {
        // 漏签文件：往已签名包里塞未受保护文件 → FILE_COVERAGE_INCOMPLETE
        val (bytes, ring) = signedPackage()
        val smuggled = replaceEntry(bytes, add = "evil/payload.js" to "x".toByteArray(Charsets.UTF_8))
        val result = verify(smuggled, ring) as PackageVerifyResult.Rejected
        assertEquals(PluginPackageVerifier.REASON_FILE_COVERAGE, result.reason)
    }

    @Test
    fun `unsigned package is reported as rejected for market`() {
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .file("skills/summarize/SKILL.md", "x")
            .file("mcp/servers.json", "{}")
            .build()
        val result = verify(bytes, keyring) as PackageVerifyResult.Rejected
        assertEquals(PluginPackageVerifier.REASON_SIGNATURE_MISSING, result.reason)
    }

    // ── keyring 信任模型 ────────────────────────────────────────────

    @Test
    fun `unknown key id is rejected`() {
        val otherPair = Es256Verifier.generateKeyPair()
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .file("skills/summarize/SKILL.md", "x")
            .file("mcp/servers.json", "{}")
            .sign(otherPair, "stranger-key")  // 用陌生 key 签
            .build()
        val result = verify(bytes, keyring) as PackageVerifyResult.Rejected
        assertEquals(PluginPackageVerifier.REASON_CRYPTO, result.reason)
        assertTrue(result.detail.contains("stranger-key"))
    }

    @Test
    fun `wrong key signature mismatches`() {
        // keyId 在 keyring 里，但签名出自另一把私钥（张冠李戴）
        val strangerPair = Es256Verifier.generateKeyPair()
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .file("skills/summarize/SKILL.md", "x")
            .file("mcp/servers.json", "{}")
            .sign(strangerPair, keyId)  // 声明 test-key 但用陌生私钥签
            .build()
        val result = verify(bytes, keyring) as PackageVerifyResult.Rejected
        assertEquals(PluginPackageVerifier.REASON_CRYPTO, result.reason)
    }

    @Test
    fun `corrupt key in keyring is bad key`() {
        val (bytes, _) = signedPackage()
        val result = verify(bytes, mapOf(keyId to "!!!not-base64!!!")) as PackageVerifyResult.Rejected
        assertEquals(PluginPackageVerifier.REASON_CRYPTO, result.reason)
    }

    @Test
    fun `non-es256 algorithm in signature file is rejected`() {
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .file("skills/summarize/SKILL.md", "x")
            .file("mcp/servers.json", "{}")
            .sign(keyPair, keyId)
            .build()
        // 直接改写 SIGNATURE 文件的 algorithm 字段（RS256）—— 会连带 digest 失配或算法拒绝
        val tampered = replaceEntry(bytes, ManifestPaths.SIGNATURE) { raw ->
            String(raw, Charsets.UTF_8).replace("ES256", "RS256").toByteArray(Charsets.UTF_8)
        }
        val result = verify(tampered, keyring) as PackageVerifyResult.Rejected
        assertTrue(
            "改 algorithm 必须被拒（ALGORITHM_UNSUPPORTED 或 digest/crypto 失败）: ${result.reason}",
            result.reason in setOf(
                PluginPackageVerifier.REASON_ALGORITHM,
                PluginPackageVerifier.REASON_CRYPTO,
                PluginPackageVerifier.REASON_DIGEST_MISMATCH,
            )
        )
    }

    // ── DER ↔ raw 转换（编码互操作内核）────────────────────────────

    @Test
    fun `der raw conversion round-trips`() {
        val raw = ByteArray(64) { (it * 7).toByte() }
        assertEquals(raw.size, 64)
        val der = Es256Verifier.rawToDer(raw)
        val back = Es256Verifier.derToRaw(der)
        assertTrue("raw → DER → raw 无损", raw.contentEquals(back))
    }

    @Test
    fun `der with high-bit integer gets leading zero handled`() {
        // r 首字节最高位置 1（如 0xFF）：DER 要求补零保持正号，转换须无损
        val raw = ByteArray(64) { if (it == 0 || it == 32) 0xFF.toByte() else 0x11 }
        val back = Es256Verifier.derToRaw(Es256Verifier.rawToDer(raw))
        assertTrue(raw.contentEquals(back))
    }

    @Test
    fun `jca signature itself round-trips through conversions`() {
        // 端到端内核：JCA sign(DER) → derToRaw → rawToDer → JCA verify —— 必须通过
        val payload = "theocracy-es256-interop".toByteArray(Charsets.UTF_8)
        val signer = java.security.Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(payload)
        val jcaDer = signer.sign()
        val raw = Es256Verifier.derToRaw(jcaDer)
        assertEquals(64, raw.size)
        val verifier = java.security.Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.public)
        verifier.update(payload)
        assertTrue("DER→raw→DER 后 JCA 验签必须通过", verifier.verify(Es256Verifier.rawToDer(raw)))
    }

    @Test
    fun `truncated der is rejected`() {
        val raw = ByteArray(64) { 0x0A }
        val der = Es256Verifier.rawToDer(raw)
        try {
            Es256Verifier.derToRaw(der.copyOf(der.size - 3))
            throw AssertionError("截断 DER 必须抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("截断"))
        }
    }

    // ── 工具：包内替换/追加 entry（攻击形态构造）────────────────────

    private fun replaceEntry(
        bytes: ByteArray,
        target: String? = null,
        add: Pair<String, ByteArray>? = null,
        transform: (ByteArray) -> ByteArray = { it },
    ): ByteArray {
        val pkg = (PluginPackageReader.read(bytes) as PackageReadResult.Success).pkg
        val rebuilt = PackageBuilder()
        pkg.entries.forEach { (path, data) ->
            if (path == target) {
                // 替换目标：直接写变换后的字节（绕过 builder 的 manifest 注入路径）
                rebuilt.file(path, transform(data))
            } else {
                rebuilt.file(path, data)
            }
        }
        add?.let { (p, d) -> rebuilt.file(p, d) }
        // 注意：重打包不重签 —— 攻击场景本来就是「签名还是旧的，内容已换」
        return rebuilt.build()
    }
}
