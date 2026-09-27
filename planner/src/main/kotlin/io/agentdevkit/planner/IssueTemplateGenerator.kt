package io.agentdevkit.planner

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json

data class IssueRequest(
    val title: String,
    val severity: String = "MEDIUM",
    val priority: String = "high",
    val component: String = "core",
    val targetFiles: List<String> = emptyList(),
    val targetSymbols: List<String> = emptyList(),
    val targetModules: List<String> = emptyList(),
    val context: String = "",
    val needed: List<String> = emptyList(),
    val openQuestions: Boolean = false,
)

data class IssueResult(
    val id: String,
    val file: File,
    val markdown: String,
)

object IssueTemplateGenerator {
    private val ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC)

    fun scaffold(request: IssueRequest, repoRoot: File): IssueResult {
        val now = Instant.now()
        val timestamp = ID_FORMATTER.format(now)
        val slug = slugify(request.title)
        val issuesDir = File(repoRoot, "docs/internals/backlog")
        issuesDir.mkdirs()
        var sequence = 0
        var filename = "issue-$timestamp-$slug.md"
        var file = File(issuesDir, filename)
        while (file.exists()) {
            sequence++
            filename = "issue-$timestamp-${"%02d".format(sequence)}-$slug.md"
            file = File(issuesDir, filename)
        }

        val neededFormatted = if (request.needed.isEmpty()) {
            "1. Define implementation steps."
        } else {
            request.needed.mapIndexed { idx, step -> "${idx + 1}. $step" }.joinToString("\n")
        }

        val contextFormatted = request.context.ifBlank {
            "Document why this change or feature is required."
        }

        val modulesYaml = if (request.targetModules.isEmpty()) {
            "[]"
        } else {
            "\n" + request.targetModules.joinToString("\n") { "  - ${Json.encodeToString(it)}" }
        }

        val filesYaml = if (request.targetFiles.isEmpty()) {
            "[]"
        } else {
            "\n" + request.targetFiles.joinToString("\n") { "  - ${Json.encodeToString(it)}" }
        }

        val symbolsYaml = if (request.targetSymbols.isEmpty()) {
            "[]"
        } else {
            "\n" + request.targetSymbols.joinToString("\n") { "  - ${Json.encodeToString(it)}" }
        }

        val id = filename.removeSuffix(".md")
        val markdown = """
---
title: ${Json.encodeToString(request.title)}
severity: "${request.severity.uppercase()}"
status: "open"
priority: ${request.priority.lowercase()}
dependencies: []
component: ${Json.encodeToString(request.component)}
target_modules: $modulesYaml
target_files: $filesYaml
target_symbols: $symbolsYaml
open_questions: ${request.openQuestions}
---

# 🟡 [Severity: ${request.severity.uppercase()}]: ${request.title}

**Context:**
$contextFormatted

**Needed:**
$neededFormatted

---
<!-- id: $id  file: $filename -->
""".trimIndent() + "\n"

        file.writeText(markdown)
        return IssueResult(
            id = id,
            file = file,
            markdown = markdown,
        )
    }

    fun slugify(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(50)
            .ifBlank { "issue" }
    }

    /** Human-facing schema-v2 skeleton. Legacy scaffold remains for existing callers. */
    fun scaffoldStructured(title: String, repoRoot: File, targetFiles: List<String>): IssueResult {
        require(targetFiles.isNotEmpty()) { "Provide at least one target file for scheduling" }
        require(targetFiles.size <= 32) { "Provide at most 32 target files" }
        val canonicalRoot = repoRoot.canonicalFile
        val targets = targetFiles.map { raw ->
            require(raw.isNotBlank() && raw.length <= 512 && !File(raw).isAbsolute && !raw.any(Char::isISOControl) &&
                ".." !in raw.split('/', '\\')) { "Target must be a repository-relative file path: $raw" }
            val file = File(canonicalRoot, raw).canonicalFile
            require(file != canonicalRoot && file.toPath().startsWith(canonicalRoot.toPath()) && (!file.exists() || file.isFile)) {
                "Target must be a file inside the repository: $raw"
            }
            file.relativeTo(canonicalRoot).invariantSeparatorsPath
        }.distinct()
        val created = scaffold(IssueRequest(title), repoRoot)
        val text = """---
schema_version: 2
document_type: issue
id: ${created.id}
title: ${Json.encodeToString(title)}
severity: MEDIUM
status: open
priority: 0
dependencies: []
target_files:
${targets.joinToString("\n") { "  - ${Json.encodeToString(it)}" }}
---

## Context
Write what should change and why. A short description is enough.

## Targets
${targets.joinToString("\n")}
"""
        created.file.writeText(text)
        return created.copy(markdown = text)
    }
}
