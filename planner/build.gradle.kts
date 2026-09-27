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
    api(project(":planner-core"))
    implementation(libs.kotlinxSerialization)
    implementation(libs.jackson.yaml)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
tasks.test {
    useJUnitPlatform()
    systemProperty("fixture.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
