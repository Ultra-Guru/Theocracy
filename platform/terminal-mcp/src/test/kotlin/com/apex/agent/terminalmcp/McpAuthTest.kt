package com.apex.agent.terminalmcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * McpAuth 单测：令牌熵形态 / 常量时间校验 / Bearer 提取 / Origin 白名单。
 */
class McpAuthTest {

    @Test
    fun `generated token is 43-char base64url`() {
        val token = McpAuth.generateToken()
        assertEquals(43, token.length)
        assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `generated tokens are unique`() {
        val rng = SecureRandom()
        val tokens = (1..64).map { McpAuth.generateToken(rng) }.toSet()
        assertEquals(64, tokens.size)
    }

    @Test
    fun `token validation accepts exact match`() {
        val token = McpAuth.generateToken()
        assertTrue(McpAuth.isTokenValid(token, token))
    }

    @Test
    fun `token validation rejects wrong null and blank`() {
        val token = McpAuth.generateToken()
        assertFalse(McpAuth.isTokenValid(token, "wrong-token"))
        assertFalse(McpAuth.isTokenValid(token, null))
        assertFalse(McpAuth.isTokenValid(token, ""))
        assertFalse(McpAuth.isTokenValid(token, token.substring(0, 40)))
    }

    @Test
    fun `bearer extraction handles case and whitespace`() {
        assertEquals("t", McpAuth.extractBearer("Bearer t"))
        assertEquals("t", McpAuth.extractBearer("bearer t"))
        assertEquals("t", McpAuth.extractBearer("  Bearer   t  "))
        assertNull(McpAuth.extractBearer("Basic dXNlcjpwYXNz"))
        assertNull(McpAuth.extractBearer(null))
        assertNull(McpAuth.extractBearer("Bearer "))
    }

    @Test
    fun `local origins are allowed`() {
        assertTrue(McpAuth.isLocalOrigin("http://localhost:3000"))
        assertTrue(McpAuth.isLocalOrigin("http://127.0.0.1"))
        assertTrue(McpAuth.isLocalOrigin("https://localhost"))
        assertTrue(McpAuth.isLocalOrigin("http://[::1]:8080"))
    }

    @Test
    fun `remote and exotic origins are rejected`() {
        assertFalse(McpAuth.isLocalOrigin("http://evil.example.com"))
        assertFalse(McpAuth.isLocalOrigin("https://127.0.0.1.evil.example.com"))
        assertFalse(McpAuth.isLocalOrigin("chrome-extension://abcdef"))
        assertFalse(McpAuth.isLocalOrigin("file://"))
        assertFalse(McpAuth.isLocalOrigin("null"))
    }
}
