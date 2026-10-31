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
    // PR 1/10（terminal-mcp 传输基座）：本模块自选约束 —— 纯传输 + 协议层，
    // 不依赖 platform:terminal（PR 2/10 接入 TerminalRuntime 工具面时再引入），
    // 保证传输基座可独立用 JVM 单测完整验证（HttpURLConnection 直打真实
    // NanoHTTPD 实例，无 mock 框架 —— 与全仓手写 fake 纪律一致）。
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
