plugins {
    alias(libs.plugins.kotlin)
    application
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
    implementation(project(":planner"))
    implementation(project(":agent-acp"))
    implementation(project(":scheduler"))
    implementation(libs.kotlinxSerialization)
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
application { mainClass.set("io.agentdevkit.planner.app.PlannerMainKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
tasks.test {
    useJUnitPlatform()
    systemProperty("fixture.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
