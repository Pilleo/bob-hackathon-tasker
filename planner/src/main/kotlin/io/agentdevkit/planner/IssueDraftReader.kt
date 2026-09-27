package io.agentdevkit.planner

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

object IssueDraftReader {
    fun read(file: File, root: File): DraftReadResult = boundary({ DraftReadResult(null, listOf("Cannot read issue: ${it.message}")) }) {
        val backlog = File(root, "docs/internals/backlog").canonicalFile
        if (!file.isFile || file.canonicalFile.parentFile != backlog || !file.name.startsWith("issue-") || file.extension != "md") {
            return@boundary DraftReadResult(null, listOf("Issue must be a regular backlog issue file within ${backlog.path}"))
        }
        val structured = PlanningDocumentReader.read(file, root)
        when (structured) {
            is DocumentRead.Valid -> return@boundary DraftReadResult(
                IssueDraft(structured.document.id, structured.document.title,
                    MarkdownSections.scan(structured.document.authoredText).firstOrNull { it.heading == "Context" }?.body.orEmpty(),
                    structured.document.steps.map { it.title }, structured.document.criteria.map { it.body },
                    structured.document.steps.flatMap { it.targets }.map { it.file }.distinct(),
                    structured.document.steps.flatMap { it.targets }.mapNotNull { it.symbol }.distinct(),
                    structured.document.questions.filter { it.answer.isNullOrBlank() }.map { it.question }, structured.document.authoredText,
                ), emptyList())
            is DocumentRead.Invalid -> return@boundary DraftReadResult(null, structured.problems.map { "${it.path}:${it.line}: ${it.message}" })
            is DocumentRead.Legacy -> Unit
        }
        if (file.length() > 64_000) return@boundary DraftReadResult(null, listOf("Issue exceeds 64000-byte limit"))
        val text = file.readText()
        val parts = Regex("\\A---\\s*\n(.*?)\n---\\s*\n(.*)\\z", RegexOption.DOT_MATCHES_ALL).matchEntire(text)
            ?: return@boundary DraftReadResult(null, listOf("Issue must begin with YAML frontmatter"))
        val frontmatter = parts.groupValues[1]
        val body = parts.groupValues[2]
        val title = scalar(frontmatter, "title").orEmpty()
        val status = scalar(frontmatter, "status")
        val context = section(body, "Context")
        val needed = bullets(section(body, "Needed"))
        val acceptance = bullets(section(body, "Acceptance criteria"))
        val questions = bullets(section(body, "Open questions"))
        val questionFlag = scalar(frontmatter, "open_questions") == "true"
        val errors = buildList {
            if (title.isBlank()) add("Issue title is required")
            if (status != "open") add("Only open issues can be submitted for review")
            if (context.isBlank() || context.contains("Document why this change or feature is required.", ignoreCase = true)) add("Replace the Context placeholder")
            if (needed.isEmpty() || needed.any { it == "Define implementation steps." }) add("Replace the Needed placeholder")
            if (acceptance.isEmpty() || acceptance.any { it == "Describe an observable success case." }) add("Acceptance criteria must contain observable behavior, not the editor placeholder")
            if (questions.any { it == "Replace this prompt with a real question, or remove it." }) add("Open questions contain an editor placeholder; replace or remove it")
            if (questionFlag && questions.isEmpty()) add("Open questions must list unresolved questions")
            if (!questionFlag && questions.isNotEmpty()) add("Set open_questions: true when questions are listed")
        }
        DraftReadResult(
            IssueDraft(file.nameWithoutExtension, title, context, needed, acceptance,
                values(frontmatter, "target_files"), values(frontmatter, "target_symbols"), questions, text),
            errors,
        )
    }

    private fun scalar(yaml: String, name: String): String? = Regex("(?m)^${Regex.escape(name)}:\\s*(.*)$")
        .find(yaml)?.groupValues?.get(1)?.trim()?.let { raw ->
            runCatching { (Json.parseToJsonElement(raw) as JsonPrimitive).content }.getOrDefault(raw.trim('"', '\''))
        }

    private fun values(yaml: String, name: String): List<String> {
        val raw = Regex("(?m)^${Regex.escape(name)}:[ \\t]*(.*)$").find(yaml) ?: return emptyList()
        val inline = raw.groupValues[1].trim()
        if (inline.startsWith("[")) return runCatching {
            (Json.parseToJsonElement(inline) as JsonArray).map { (it as JsonPrimitive).content }
        }.getOrDefault(emptyList())
        return yaml.substring(raw.range.last + 1).trimStart('\n').lineSequence().takeWhile { it.startsWith("  - ") }
            .map { item -> item.trimStart().removePrefix("- ").trim().let { runCatching { (Json.parseToJsonElement(it) as JsonPrimitive).content }.getOrDefault(it.trim('"')) } }
            .toList()
    }

    private fun section(body: String, name: String): String {
        val remainder = body.substringAfter("**$name:**", "")
        val end = Regex("(?m)^\\*\\*[A-Za-z][^\\n]*?:\\*\\*|^---\\s*$").find(remainder)?.range?.first ?: remainder.length
        return remainder.take(end).trim()
    }

    private fun bullets(text: String): List<String> = text.lines().mapNotNull { line ->
        Regex("^\\s*(?:- |\\d+\\. )(.+)$").matchEntire(line)?.groupValues?.get(1)?.trim()
    }
}
