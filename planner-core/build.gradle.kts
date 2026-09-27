plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.kotlinPluginSerialization)
    `java-library`
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
repositories { mavenCentral() }
dependencies {
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
tasks.test { useJUnitPlatform() }
