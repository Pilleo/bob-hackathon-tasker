package io.agentdevkit.planner

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class Elaboration(val success: Boolean, val message: String)

object IssueElaborator {
    fun draftProblem(text: String): String? {
        val context = section(text, "Context")
        return if (context.isBlank() || context.startsWith("Write what should change", ignoreCase = true))
            "Write a description in Context before elaboration" else GeneratedSections.problem(text)
    }

    fun prompt(text: String): String {
        return buildString {
            appendLine("Expand the complete human issue below into one schema_version: 2 Markdown issue. Return only the complete document, starting with YAML frontmatter; do not edit files directly.")
            appendLine("Preserve every original YAML frontmatter field including mandatory target_files, and all existing human-authored Context, Targets, criteria, steps, verification, answers and review notes. Declared target_files are used to avoid scheduling overlapping issues.")
            appendLine("Include observable ## Acceptance criteria with ### AC1 — ..., ## Changes with ### S1 — ..., and ## Verification with ### T1 — ... .")
            appendLine("Each S1 YAML block needs acceptance: [AC1], targets: [{file: ..., kind: existing|new, symbol: ...}], and verification: [T1]. Do not add per-step resource claims; scheduling uses frontmatter target_files.")
            appendLine("Each T1 YAML block needs kind: existing|new, file: relative/path, and an optional symbol. Explain meaningful checks.")
            appendLine("For uncertain decisions add a question in exactly this schema (resolve_through may be investigation, experiment or decision):")
            appendLine("## Questions\n### Q1 — Decide null handling\n```yaml\nblocks: [S1]\nresolve_through: decision\n```\n**Question:** Should null be rejected?\n**Why it matters:** This changes the public API.\n**Answer:**")
            appendLine("Never answer a decision question yourself: leave **Answer:** blank for the human. Never remove, rename, reclassify, or redirect an existing decision question; revise generated change steps instead. For investigation or experiment include **Investigation:** describing the concrete action and answer only after carrying it out. Preserve any human answers already present.")
            appendLine("Issue document:\n$text")
        }
    }

    fun inspect(agentText: String): DocumentRead = PlanningDocumentReader.parse(GeneratedSections.strip(documentText(agentText)))

    private fun documentText(agentText: String): String {
        val response = agentText.trim()
        return if (response.startsWith("```markdown\n") && response.endsWith("\n```"))
            response.removePrefix("```markdown\n").removeSuffix("\n```").trim() else response
    }

    fun apply(issue: File, agentText: String, expectedText: String? = null): Elaboration {
        if (Files.isSymbolicLink(issue.toPath()) || !issue.isFile || issue.length() > 262_144) return Elaboration(false, "Issue must be a bounded regular file")
        val human = issue.readText()
        if (expectedText != null && human != expectedText) return Elaboration(false, "Issue changed while the agent was elaborating")
        val original = PlanningDocumentReader.parse(human) as? DocumentRead.Valid
            ?: return Elaboration(false, "Issue is not a valid schema-v2 document")
        draftProblem(human)?.let { return Elaboration(false, it) }
        val context = section(human, "Context")
        val plain = GeneratedSections.strip(documentText(agentText))
        val document = try { GeneratedSections.preserveAnswers(original.document, plain) }
            catch (error: IllegalArgumentException) { return Elaboration(false, error.message.orEmpty()) }
        val parsed = PlanningDocumentReader.parse(document)
        if (parsed !is DocumentRead.Valid) {
            val reason = (parsed as? DocumentRead.Invalid)?.problems?.joinToString { it.message } ?: "Agent did not return a schema-v2 issue"
            return Elaboration(false, "Agent plan is not usable: $reason")
        }
        if (parsed.document.kind != original.document.kind || parsed.document.id != original.document.id || parsed.document.title != original.document.title) {
            return Elaboration(false, "Agent plan changed issue identity")
        }
        val frontmatter = Regex("\\A---[ \\t]*\\r?\\n(.*?)\\r?\\n---[ \\t]*\\r?\\n", RegexOption.DOT_MATCHES_ALL)
        if (frontmatter.find(human)?.groupValues?.get(1) != frontmatter.find(document)?.groupValues?.get(1)) {
            return Elaboration(false, "Agent plan changed human-authored frontmatter")
        }
        if (context != section(document, "Context")) return Elaboration(false, "Agent plan changed the human description")
        val generated = GeneratedSections.owned(human)
        val owned = listOf("Targets", "Acceptance criteria", "Changes", "Verification", "Questions", "Review notes", "Goal", "Non-goals", "Risks")
        val authored = MarkdownSections.scan(ReviewProjection.authoredBytes(human).toString(Charsets.UTF_8))
        val proposed = MarkdownSections.scan(ReviewProjection.authoredBytes(document).toString(Charsets.UTF_8))
        for (name in owned) {
            if (generated.any { id -> (id == "AC" && name == "Acceptance criteria") || (id == "S" && name == "Changes") ||
                    (id == "T" && name == "Verification") || (id == "Q" && name == "Questions") }) continue
            val existing = authored.firstOrNull { it.level == 2 && it.heading == name }?.body?.trim().orEmpty()
            if (existing.isBlank() || (name == "Targets" && existing.startsWith("Optional."))) continue
            val replacement = proposed.firstOrNull { it.level == 2 && it.heading == name }?.body?.trim().orEmpty()
            if (!replacement.startsWith(existing)) return Elaboration(false, "Agent plan changed human-authored $name; revise it manually instead")
        }
        if (parsed.document.criteria.isEmpty() || parsed.document.steps.isEmpty() || parsed.document.verification.isEmpty() ||
            parsed.document.steps.any { it.acceptance.isEmpty() || it.verification.isEmpty() }) {
            return Elaboration(false, "Agent plan is incomplete: criteria, linked steps and verification are required")
        }
        val published = GeneratedSections.mark(human, document)
        val target = issue.toPath()
        val temp = Files.createTempFile(target.parent, ".elaboration-", ".tmp")
        try {
            val newline = if ("\r\n" in human) "\r\n" else "\n"
            Files.writeString(temp, "${published.trimEnd()}$newline")
            if (issue.readText() != human || Files.isSymbolicLink(target)) return Elaboration(false, "Issue changed while saving agent plan")
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        return Elaboration(true, "Plan written to ${issue.path}. Review it, then run adk review-issue ${issue.path}")
    }

    private fun section(text: String, name: String): String {
        val body = text.substringAfter("## $name", "").substringBefore("\n## ")
        return body.lineSequence().drop(1).joinToString("\n").trim()
    }

}
