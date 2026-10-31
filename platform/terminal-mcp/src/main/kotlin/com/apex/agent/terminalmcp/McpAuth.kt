package com.apex.agent.terminalmcp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * MCP 鉴权原语：令牌生成 / 提取 / 校验。
 *
 * 三条纪律（MCP spec 2025-06-18 transports 的本地服务器安全建议）：
 * 1. 令牌高熵 —— 32 字节 SecureRandom 转 base64url 无填充（43 字符）；
 * 2. 比较常量时间 —— [MessageDigest.isEqual]，防时序侧信道逐字节猜令牌；
 * 3. 只绑定 loopback —— 由 [McpHttpServer] 构造参数保证（本类不涉及）。
 */
object McpAuth {

    /** 生成 256 位随机令牌（base64url 无填充，43 字符）。 */
    fun generateToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * 校验令牌（常量时间）。
     *
     * 空值直接拒绝 —— [presented] 为 null（未携带）与错误值同路处理，
     * 不向调用方泄露「哪种错法」。
     */
    fun isTokenValid(expected: String, presented: String?): Boolean {
        if (presented.isNullOrEmpty()) return false
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = presented.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }

    /** 从 Authorization 头提取 Bearer 令牌；非 Bearer 方案返回 null。 */
    fun extractBearer(header: String?): String? {
        val trimmed = header?.trim() ?: return null
        if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
        return trimmed.substring("Bearer ".length).trim().takeIf { it.isNotEmpty() }
    }

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

    /**
     * Origin 白名单校验（防 DNS rebinding：恶意网页让浏览器把本地端口当跳板）。
     *
     * 规则：无 Origin（原生客户端：Claude Code / RikkaHub / curl 不发）放行；
     * 有 Origin 则必须是 localhost / 127.0.0.1 / [::1] 的 http(s) 源。
     * 浏览器扩展（chrome-extension:）与 file 源一律拒绝 —— 它们拿得到
     * loopback 但不该被静默放行，需要接入时再显式扩展白名单。
     */
    fun isLocalOrigin(origin: String): Boolean {
        val o = origin.trim().lowercase()
        if (!o.startsWith("http://") && !o.startsWith("https://")) return false
        val rawHost = o.substringAfter("//").substringBefore('/')
        val host = if (rawHost.startsWith("[")) {
            rawHost.substringBefore(']') + "]"
        } else {
            rawHost.substringBefore(':')
        }
        return host in LOCAL_HOSTS
    }
}
