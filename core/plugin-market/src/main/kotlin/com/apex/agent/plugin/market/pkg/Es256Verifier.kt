package com.apex.agent.plugin.market.pkg

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * ES256 验签/签名（Spec §8.3：ECDSA P-256 + SHA-256，JOSE raw r‖s 编码）。
 *
 * ## 编码转换的必要性
 *
 * JCA 的 `SHA256withECDSA` 产出/消费 **ASN.1 DER**（SEQUENCE{r,s}），
 * 而 ES256（JOSE RFC 7518）是 **raw 64 字节 r‖s**（各 32 字节大端）。
 * .agentpkg 的签名域采用 JOSE 编码 —— 与市场后端（Node/Go 生态的
 * ES256 工具链）直接互操作；JCA 侧做 [derToRaw]/[rawToDer] 双向转换。
 *
 * ## keyring 信任模型
 *
 * 验签公钥来自**市场预置 keyring**（keyId → X.509 base64），
 * 不从包内取公钥（自证无效）。keyId 支持轮换：多把 key 并存，
 * 按 SIGNATURE 声明的 keyId 取用；keyring 不含该 keyId 即拒绝
 * （未知发布者）。
 */
object Es256Verifier {

    /** raw r‖s 固定长度（两个 32 字节大端整数）。 */
    const val RAW_SIGNATURE_BYTES = 64

    /** 曲线参数（P-256 = secp256r1 = prime256v1）。 */
    private const val CURVE = "secp256r1"

    // ── 验签 ────────────────────────────────────────────────────────

    /**
     * 验签入口：canonical 载荷 + JOSE raw 签名 + keyring。
     *
     * @param canonicalBytes [SignatureCodec.canonicalBytes] 复现的载荷
     * @param signatureB64Url base64url（无 padding）的 64 字节 r‖s
     * @param keyring keyId → X.509 公钥 base64（标准 base64，带 padding）
     * @return 三态：keyId 缺失 / 签名非法 / 验签通过
     */
    fun verify(
        canonicalBytes: ByteArray,
        signatureB64Url: String,
        keyId: String,
        keyring: Map<String, String>,
    ): VerifyOutcome {
        val publicB64 = keyring[keyId]
            ?: return VerifyOutcome.UNKNOWN_KEY
        val publicBytes = try {
            Base64.getDecoder().decode(publicB64)
        } catch (e: IllegalArgumentException) {
            return VerifyOutcome.BAD_KEY
        }
        val publicKey = try {
            KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(publicBytes)) as ECPublicKey
        } catch (e: Exception) {
            return VerifyOutcome.BAD_KEY
        }
        val raw = try {
            decodeBase64Url(signatureB64Url)
        } catch (e: IllegalArgumentException) {
            return VerifyOutcome.BAD_SIGNATURE
        }
        if (raw.size != RAW_SIGNATURE_BYTES) return VerifyOutcome.BAD_SIGNATURE
        val der = try {
            rawToDer(raw)
        } catch (e: IllegalArgumentException) {
            return VerifyOutcome.BAD_SIGNATURE
        }
        return try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(canonicalBytes)
            if (verifier.verify(der)) VerifyOutcome.VERIFIED else VerifyOutcome.MISMATCH
        } catch (e: Exception) {
            VerifyOutcome.BAD_SIGNATURE
        }
    }

    /** 验签三态（[VerifyOutcome.VERIFIED] 之外全部视作拒绝）。 */
    enum class VerifyOutcome { VERIFIED, MISMATCH, UNKNOWN_KEY, BAD_KEY, BAD_SIGNATURE }

    // ── 签名（打包器 / 市场后端 CLI / 测试基建）─────────────────────

    /** 生成 P-256 密钥对（测试与开发 CLI 用；市场后端密钥由 Keystore 托管）。 */
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(CURVE))
        }.generateKeyPair()

    /** 公钥 X.509 base64（keyring 形态）。 */
    fun publicKeyBase64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** 私钥 PKCS#8 base64（测试基建透传用，不进生产路径）。 */
    fun privateKeyBase64(keyPair: KeyPair): String =
        Base64.getEncoder().encodeToString(keyPair.private.encoded)

    /** 对载荷签名，返回 base64url（无 padding）raw r‖s。 */
    fun sign(canonicalBytes: ByteArray, keyPair: KeyPair): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(canonicalBytes)
        val der = signer.sign()
        return encodeBase64Url(derToRaw(der))
    }

    /** 从 PKCS#8 base64 恢复私钥（测试基建用）。 */
    fun privateKeyFromBase64(b64: String): java.security.PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))

    // ── DER ↔ raw 转换 ──────────────────────────────────────────────

    /**
     * ASN.1 DER（SEQUENCE{INTEGER r, INTEGER s}）→ raw 64 字节。
     * 拒绝非规范形态：长度不符、非最短整数编码（DER 规定 MINIMAL_OCTETS）。
     */
    fun derToRaw(der: ByteArray): ByteArray {
        var i = 0
        fun readByte(): Int {
            if (i >= der.size) throw IllegalArgumentException("DER 截断")
            return der[i++].toInt() and 0xFF
        }
        fun readLength(): Int {
            val first = readByte()
            if (first and 0x80 == 0) return first
            val count = first and 0x7F
            if (count == 0 || count > 4) throw IllegalArgumentException("DER 长度字段非法")
            var len = 0
            repeat(count) { len = (len shl 8) or readByte() }
            return len
        }
        fun readInteger(): ByteArray {
            if (readByte() != 0x02) throw IllegalArgumentException("期望 INTEGER 标签")
            val len = readLength()
            if (len == 0 || len > 33) throw IllegalArgumentException("INTEGER 长度非法: $len")
            val value = ByteArray(len) { readByte().toByte() }
            // 去掉前导零（大端）
            var start = 0
            while (start < value.size - 1 && value[start] == 0.toByte()) start++
            return value.copyOfRange(start, value.size)
        }
        if (readByte() != 0x30) throw IllegalArgumentException("期望 SEQUENCE 标签")
        readLength()
        val r = readInteger()
        val s = readInteger()
        if (i != der.size) throw IllegalArgumentException("DER 尾部多余字节")
        if (r.size > 32 || s.size > 32) throw IllegalArgumentException("整数超出 32 字节")
        val out = ByteArray(RAW_SIGNATURE_BYTES)
        r.copyInto(out, 32 - r.size)
        s.copyInto(out, 64 - s.size)
        return out
    }

    /** raw 64 字节 → ASN.1 DER（正数最高位置 1 时补零，保证正号语义）。 */
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == RAW_SIGNATURE_BYTES) { "raw 签名须为 64 字节" }
        val r = raw.copyOfRange(0, 32)
        val s = raw.copyOfRange(32, 64)
        fun encodeInt(v: ByteArray): ByteArray {
            val needsPad = v[0].toInt() and 0x80 != 0
            val body = if (needsPad) byteArrayOf(0) + v else v
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
        val rEnc = encodeInt(r)
        val sEnc = encodeInt(s)
        val body = rEnc + sEnc
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    // ── base64url（无 padding）─────────────────────────────────────

    fun encodeBase64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decodeBase64Url(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text)
}
