plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // 防御式 IO 留痕（core:logging 纯 JVM，无 Android 依赖）
    implementation(project(":core:logging"))
    implementation(libs.serialization.json)

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
}
