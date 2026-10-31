package com.apex.agent.plugin.market.skill

/**
 * Skill 完整性校验（Spec §4.2，MCP Skills extension 完整性模型）。
 *
 * 市场分发链：Ingest 时为每个 SKILL.md 计算 SHA-256 注入
 * [SkillManifest.integrityDigest]；运行时 **Activation 加载正文前**复核 ——
 * digest 不匹配即拒绝（抛 [SkillIntegrityException]）。
 *
 * 与包级 ES256（PR-A）的分工：包签名守「整包来源可信」（市场分发面），
 * digest 守「单 skill 正文未被二次改动」（安装后本地校验面）——
 * 后者不需要 keyring，任何持有 manifest 的实现都能复核。
 */
object SkillIntegrity {

    /** SKILL.md 字节的 SHA-256（小写 hex）。 */
    fun digestOf(skillFileBytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(skillFileBytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * 复核 SKILL.md 字节与 manifest 声明的 digest。
     *
     * - manifest 未携带 digest（本地开发 skill）：**放行**（无声明即无契约）；
     * - 携带且匹配：放行；
     * - 携带且不匹配：抛 [SkillIntegrityException] —— Activation 拒绝加载。
     */
    fun verify(manifest: SkillManifest, skillFileBytes: ByteArray) {
        val declared = manifest.integrityDigest ?: return
        val actual = digestOf(skillFileBytes)
        if (!declared.equals(actual, ignoreCase = true)) {
            throw SkillIntegrityException(
                manifest = manifest,
                declaredDigest = declared,
                actualDigest = actual,
            )
        }
    }
}

/** 完整性复核失败 —— Activation 拒绝加载（Spec §4.2 硬性约束）。 */
class SkillIntegrityException(
    val manifest: SkillManifest,
    val declaredDigest: String,
    val actualDigest: String,
) : IllegalStateException(
    "skill \"${manifest.name}\"（${manifest.skillDir}）完整性校验失败：" +
        "manifest 声明 $declaredDigest，SKILL.md 实际 $actualDigest —— 拒绝加载"
)
