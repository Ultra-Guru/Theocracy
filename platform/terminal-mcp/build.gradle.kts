import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.apex.agent.terminalmcp"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // PR 2/10 起接入 TerminalRuntime 冻结门面（run/list_sessions/send_signal/
    // resize 工具面）。platform:terminal 对 :terminal-emulator 是 implementation
    // （不传递）—— 门面的 styledScreenFlow 签名引用 TerminalRenderSnapshot，
    // 本模块需显式声明（与 app 的先例同款）。
    implementation(project(":platform:terminal"))
    implementation(project(":terminal-emulator"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    // NanoHTTPD 2.3.1（Maven Central 终版，功能冻结但久经考验）：thread-per-
    // connection 模型与 MCP Streamable HTTP 的「纯 JSON 响应」最小合规路线
    // （spec 2025-06-18 transports：服务器可对 POST 回 application/json，
    // 客户端必须支持）匹配。官方 MCP Kotlin SDK 需 Ktor 全家桶且 RikkaHub
    // 维护了自己的 fork —— Android 内嵌场景偏重，选型详见 PR 描述 ADR。
    implementation(libs.nanohttpd)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
    }
}
