import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.kotlinPluginSerialization) apply false
}

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Rejects dependencies from a reusable module into its hosts."
    doLast {
        val allowed = mapOf(
            "planner-core" to emptySet<String>(),
            "planner" to setOf(":planner-core"),
            "agent-acp" to setOf(":planner", ":planner-core"),
            "planner-app" to setOf(":planner", ":planner-core", ":agent-acp", ":scheduler"),
            "scheduler" to emptySet<String>(),
        )
        for ((name, dependencies) in allowed) {
            val module = project(":$name")
            for (configuration in listOf("compileClasspath", "runtimeClasspath", "testRuntimeClasspath")) {
                module.configurations.getByName(configuration).incoming.resolutionResult.allComponents.forEach { component ->
                    val id = component.id
                    if (id is ProjectComponentIdentifier && id.projectPath != module.path && id.projectPath !in dependencies) {
                        throw GradleException("${module.path} may depend only on $dependencies; $configuration depends on ${id.projectPath}")
                    }
                }
            }
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Verifies every portable planner module and their dependency graph."
    dependsOn(":planner-core:check", ":planner:check", ":agent-acp:check", ":planner-app:check", ":scheduler:check", "checkModuleBoundaries")
}
