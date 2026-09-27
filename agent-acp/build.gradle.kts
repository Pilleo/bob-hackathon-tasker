plugins {
    alias(libs.plugins.kotlin)
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
    api(project(":planner"))
    implementation(libs.kotlinxSerialization)
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
