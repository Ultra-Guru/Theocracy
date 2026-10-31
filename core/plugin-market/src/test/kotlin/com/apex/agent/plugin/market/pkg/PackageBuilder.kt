package com.apex.agent.plugin.market.pkg

import com.apex.agent.plugin.market.format.AgentPluginManifest
import com.apex.agent.plugin.market.format.ManifestParser
import com.apex.agent.plugin.market.format.PluginAuthor
import com.apex.agent.plugin.market.format.PluginCapability
import com.apex.agent.plugin.market.format.PluginSignatureMeta
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * .agentpkg 测试打包器（测试基建 —— 生产签名走市场后端 CLI，不进 main）。
 *
 * 职责：内存构建包字节（manifest + 任意文件 + 可选签名），
 * 供 reader/verifier 测试做 round-trip。攻击形态（篡改/漏签/坏清单）
 * 由测试在其返回的包上二次加工，打包器本身只产出「诚实包」。
 */
class PackageBuilder {

    private val files = LinkedHashMap<String, ByteArray>()
    private var manifest: AgentPluginManifest? = null

    /** 设 manifest（[ManifestParser.render] 序列化为 agent-plugin.json）。 */
    fun manifest(m: AgentPluginManifest): PackageBuilder {
        manifest = m
        return this
    }

    /** 加普通文件（路径须过 reader 的 entry 名安全检查 —— 不诚实路径直接炸测试）。 */
    fun file(path: String, bytes: ByteArray): PackageBuilder {
        PluginPackageReader.requireSafeEntryName(path)
        files[path] = bytes
        return this
    }

    fun file(path: String, text: String): PackageBuilder =
        file(path, text.toByteArray(Charsets.UTF_8))

    /**
     * 生成并附加 SIGNATURE 文件：对当前全部文件（SIGNATURE 自身除外）
     * 计算 digest 清单 + canonical 载荷 + ES256 签名。
     */
    fun sign(keyPair: KeyPair, keyId: String): PackageBuilder {
        val digests = SignatureCodec.digestEntries(filesMap())
        val canonical = SignatureCodec.canonicalBytes(
            SignatureFile(algorithm = "ES256", keyId = keyId, signedFiles = digests)
        )
        val signatureFile = SignatureFile(
            algorithm = "ES256",
            keyId = keyId,
            signedFiles = digests,
            signature = Es256Verifier.sign(canonical, keyPair),
        )
        files[ManifestPaths.SIGNATURE] = SignatureCodec.render(signatureFile).toByteArray(Charsets.UTF_8)
        return this
    }

    /** 打 zip 字节。 */
    fun build(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            filesMap().forEach { (path, bytes) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** 当前最终文件表：manifest 注入 agent-plugin.json，显式 files 覆盖同名（测试自由度）。 */
    private fun filesMap(): Map<String, ByteArray> {
        val all = LinkedHashMap<String, ByteArray>()
        manifest?.let { all[ManifestPaths.MANIFEST] = ManifestParser.render(it).toByteArray(Charsets.UTF_8) }
        all.putAll(files)
        return all
    }

    companion object {
        /** 快捷：合法样例 manifest（PR-B/C/E 测试在其上增量改造）。 */
        fun sampleManifest(
            id: String = "com.example.meeting-helper",
            name: String = "meeting-helper",
        ) = AgentPluginManifest(
            schemaVersion = "1.0",
            id = id,
            name = name,
            displayName = "会议助手",
            version = "1.2.0",
            description = "自动整理会议纪要并创建待办",
            author = PluginAuthor(name = "Example Inc", email = "dev@example.com"),
            license = "Apache-2.0",
            category = "productivity",
            tags = listOf("meeting", "notes"),
            minAndroidApi = 34,
            components = com.apex.agent.plugin.market.format.PluginComponents(
                skills = listOf("skills/summarize"),
                mcpServers = "mcp/servers.json",
            ),
            capabilities = listOf(
                PluginCapability(
                    id = "calendar.read", risk = "R1", description = "读取日历事件以生成摘要",
                ),
                PluginCapability(
                    id = "tasks.write", risk = "R2", description = "创建待办事项",
                ),
            ),
            signature = PluginSignatureMeta(
                algorithm = "ES256", keyId = "test-key",
            ),
        )
    }
}
