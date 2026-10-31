package com.apex.agent.plugin.market.pkg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [PluginPackageReader] 契约测试：round-trip、zip-slip 整包拒绝、
 * 体积防线、manifest 缺失/非法、组件对账失败路径。
 */
class PluginPackageReaderTest {

    private fun buildValidPackage(): ByteArray =
        PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .file("skills/summarize/SKILL.md", "# summarize\nfrontmatter 待 PR-B 填充")
            .file("mcp/servers.json", "{}")
            .file("README.md", "# meeting-helper")
            .build()

    @Test
    fun `valid package round-trips`() {
        val result = PluginPackageReader.read(buildValidPackage())
        assertTrue("应解包成功: ${(result as? PackageReadResult.Failure)?.issues}", result is PackageReadResult.Success)
        val pkg = (result as PackageReadResult.Success).pkg
        assertEquals("com.example.meeting-helper", pkg.manifest.id)
        assertEquals(4, pkg.entries.size)  // manifest + SKILL.md + servers.json + README
        assertTrue(pkg.entryText("README.md")!!.startsWith("# meeting-helper"))
    }

    @Test
    fun `truncated zip stream fails with PKG_CORRUPT`() {
        // ZipInputStream 对纯垃圾静默零条目（实测行为），但「合法 entry 头 +
        // 截断数据」会在读 entry 字节时抛 EOFException —— PKG_CORRUPT 走这条路
        val complete = zipOf(
            ManifestPaths.MANIFEST to "{}".toByteArray(),
            "big.bin" to ByteArray(4096) { 0x55 },
        )
        val truncated = complete.copyOf(complete.size / 2)
        val result = PluginPackageReader.read(truncated)
        val code = (result as? PackageReadResult.Failure)?.issues?.singleOrNull()?.code
        assertTrue(
            "截断流必须整包拒绝（PKG_CORRUPT 或 PKG_EMPTY），got=$code",
            code == "PKG_CORRUPT" || code == "PKG_EMPTY"
        )
    }

    @Test
    fun `garbage bytes yield empty package failure`() {
        // ZipInputStream 对垃圾不抛异常 → 0 条目 → PKG_EMPTY（防御式折叠）
        val result = PluginPackageReader.read("这根本不是zip".toByteArray(Charsets.UTF_8))
        assertEquals("PKG_EMPTY", (result as PackageReadResult.Failure).issues.single().code)
    }

    @Test
    fun `empty zip fails with PKG_EMPTY`() {
        val emptyZip = ByteArrayOutputStream().also { ZipOutputStream(it).close() }.toByteArray()
        assertEquals("PKG_EMPTY", (PluginPackageReader.read(emptyZip) as PackageReadResult.Failure).issues.single().code)
    }

    @Test
    fun `missing manifest fails`() {
        val bytes = PackageBuilder().file("README.md", "no manifest").build()
        assertEquals("MANIFEST_MISSING", (PluginPackageReader.read(bytes) as PackageReadResult.Failure).issues.single().code)
    }

    @Test
    fun `invalid manifest json fails`() {
        val bytes = zipOf(ManifestPaths.MANIFEST to "{ broken".toByteArray())
        assertEquals("MANIFEST_INVALID", (PluginPackageReader.read(bytes) as PackageReadResult.Failure).issues.single().code)
    }

    @Test
    fun `manifest semantic errors propagate`() {
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest().copy(schemaVersion = "9.9"))
            .build()
        val issues = (PluginPackageReader.read(bytes) as PackageReadResult.Failure).issues
        assertEquals(listOf("SCHEMA_VERSION"), issues.map { it.code })
    }

    @Test
    fun `declared component missing from package fails`() {
        // manifest 声明 skills/summarize 但包内没有该目录
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest())
            .build()
        val issues = (PluginPackageReader.read(bytes) as PackageReadResult.Failure).issues
        assertTrue(
            "组件缺失应报错: ${issues.map { it.code }}",
            issues.map { it.code }.containsAll(listOf("SKILL_DIR_MISSING", "MCP_SERVERS_MISSING"))
        )
    }

    // ── zip-slip 防线（核心安全测试）───────────────────────────────

    @Test
    fun `zip slip entry rejects whole package`() {
        listOf(
            "../evil.txt",                      // 父段穿越
            "/abs/evil.txt",                    // 绝对路径
            "a/../../evil.txt",                 // 混合穿越
            "C:\\win\\evil.txt",                // 盘符 + 反斜杠
            "evil\u0000.txt",                   // NUL
        ).forEach { evil ->
            val bytes = zipOf(
                ManifestPaths.MANIFEST to "{}".toByteArray(),
                evil to "x".toByteArray(),
            )
            val result = PluginPackageReader.read(bytes)
            assertTrue(
                "穿越 entry \"$evil\" 必须整包拒绝",
                result is PackageReadResult.Failure &&
                    (result.issues.single().code == "PKG_CORRUPT")
            )
        }
    }

    @Test
    fun `directory entries are skipped harmlessly`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("skills/"))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(ManifestPaths.MANIFEST))
            zos.write("{}".toByteArray())
            zos.closeEntry()
        }
        // "{}" 是形状合法但语义全空的 manifest —— 语义错误（不是 zip 错误）
        val result = PluginPackageReader.read(out.toByteArray())
        assertTrue(result is PackageReadResult.Failure)
        val codes = (result as PackageReadResult.Failure).issues.map { it.code }
        assertTrue("目录条目不应引发 zip 层失败，而是语义层报错: $codes", codes.none { it == "PKG_CORRUPT" })
    }

    @Test
    fun `oversized package is rejected`() {
        val huge = ByteArray((PluginPackageReader.MAX_PACKAGE_BYTES + 1).toInt())
        assertEquals("PKG_TOO_LARGE", (PluginPackageReader.read(huge) as PackageReadResult.Failure).issues.single().code)
    }

    @Test
    fun `warnings survive into success result`() {
        // 合法但带 warning（displayName 空 → DISPLAY_NAME_MISSING）
        val bytes = PackageBuilder()
            .manifest(PackageBuilder.sampleManifest().copy(displayName = ""))
            .file("skills/summarize/SKILL.md", "x")
            .file("mcp/servers.json", "{}")
            .build()
        val success = PluginPackageReader.read(bytes) as PackageReadResult.Success
        assertTrue(success.warnings.any { it.code == "DISPLAY_NAME_MISSING" })
    }

    // ── 工具 ───────────────────────────────────────────────────────

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
