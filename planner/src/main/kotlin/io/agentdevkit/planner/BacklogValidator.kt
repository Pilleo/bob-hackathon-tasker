package io.agentdevkit.planner

import java.io.File

object BacklogValidator {

    private val VALID_SEVERITIES = setOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "ENHANCEMENT")
    private val VALID_STATUSES = setOf("open", "in_progress", "resolved", "deferred")
    private val VALID_FILENAME_PATTERN = Regex("^issue-(?:\\d{8}[-_]\\d{6}(?:[-_]\\d{2})?|\\d{8}[-_]\\d{2,4}|\\d{1,4})[-_][a-z0-9_-]+\\.md$")

    fun validateBacklog(repoRoot: File): List<String> {
        val errors = mutableListOf<String>()
        val backlogDir = File(repoRoot, "docs/internals/backlog")
        if (backlogDir.isDirectory) {
            val allIssues = backlogDir.walkTopDown()
                .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
                .toList()

            val activeFiles = allIssues.filter { !it.absolutePath.contains("${File.separator}resolved${File.separator}") }

            for (file in activeFiles) {
                if (!VALID_FILENAME_PATTERN.matches(file.name)) {
                    errors.add("${file.name}: Invalid filename format. Must match 'issue-YYYYMMDD-HHMMSS-slug.md'")
                }

                val content = file.readText()
                if (!content.startsWith("---")) {
                    errors.add("${file.name}: Missing YAML frontmatter header (must start with '---')")
                    continue
                }

                val frontmatter = content.substringAfter("---").substringBefore("---")
                val fields = parseYamlSimple(frontmatter)

                val severity = fields["severity"]?.uppercase()
                if (severity == null || severity !in VALID_SEVERITIES) {
                    errors.add("${file.name}: Missing or invalid severity '$severity'. Allowed: $VALID_SEVERITIES")
                }

                val status = fields["status"]?.lowercase()
                if (status == null || status !in VALID_STATUSES) {
                    errors.add("${file.name}: Missing or invalid status '$status'. Allowed: $VALID_STATUSES")
                }

                val body = content.substringAfter("---").substringAfter("---")
                if (!body.contains("**Context:**")) {
                    errors.add("${file.name}: Missing mandatory '**Context:**' section")
                }
                if (!body.contains("**Needed:**")) {
                    errors.add("${file.name}: Missing mandatory '**Needed:**' section")
                }
            }

            val ids = allIssues.map { file ->
                val text = file.readText()
                val frontmatter = if (text.startsWith("---")) text.substringAfter("---").substringBefore("---") else ""
                parseYamlSimple(frontmatter)["id"]?.takeIf { it.isNotBlank() } ?: file.name.removeSuffix(".md")
            }
            ids.groupingBy { it }.eachCount().filter { it.value > 1 }.forEach { (id, count) ->
                errors.add("Duplicate issue id '$id' ($count files)")
            }

            val resolvedFiles = allIssues.filter { it.absolutePath.contains("${File.separator}resolved${File.separator}") }
            for (file in resolvedFiles) {
                val content = file.readText()
                val frontmatter = content.substringAfter("---").substringBefore("---")
                val fields = parseYamlSimple(frontmatter)
                val status = fields["status"]?.lowercase()
                if (status != "resolved") {
                    errors.add("${file.name}: Issue in 'resolved' directory must have status 'resolved' (got '$status')")
                }
            }
        }

        val planFiles = linkedSetOf<File>()
        listOf("docs/superpowers/plans", "docs/internals/plans").forEach { relative ->
            val dir = File(repoRoot, relative)
            if (dir.isDirectory) {
                dir.walkTopDown().filter { it.isFile && it.extension == "md" }.forEach { planFiles += it }
            }
        }
        val docsDir = File(repoRoot, "docs")
        if (docsDir.isDirectory) {
            docsDir.walkTopDown()
                .filter { it.isFile && it.extension == "md" }
                .filter { it.readText().contains("document_type: execution_plan") }
                .forEach { planFiles += it }
        }
        planFiles.forEach { errors += ExecutionPlan.validate(it) }

        return errors
    }

    private fun parseYamlSimple(yaml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in yaml.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || !trimmed.contains(":")) continue
            val key = trimmed.substringBefore(":").trim()
            val value = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")
            map[key] = value
        }
        return map
    }
}
