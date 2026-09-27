package io.agentdevkit.planner

import java.io.File

/** Validation for the compact, reviewable plan document that follows an intake issue. */
object ExecutionPlan {
    private val requiredHeadings = listOf(
        "## Goal", "## Acceptance criteria", "## Non-goals", "## Risks",
        "## Target claims", "## TDD cases", "## Unresolved decisions",
    )

    fun validate(file: File): List<String> {
        val text = file.readText()
        val errors = mutableListOf<String>()
        if (!text.startsWith("---")) return listOf("${file.name}: plan must begin with frontmatter")
        val frontmatter = text.substringAfter("---").substringBefore("---")
        if (!frontmatter.lines().any { it.trim() == "document_type: execution_plan" }) {
            errors += "${file.name}: document_type must be execution_plan"
        }
        if (!Regex("""(?m)^base_revision:\s*['\"]?[0-9a-f]{7,40}['\"]?\s*$""").containsMatchIn(frontmatter)) {
            errors += "${file.name}: base_revision must be a Git revision"
        }
        if (isCompactAdkPlan(text)) {
            requiredHeadings.filterNot { text.contains(it) }.forEach { errors += "${file.name}: missing $it" }
            val tdd = text.substringAfter("## TDD cases", "").substringBefore("## ").trim()
            if (!validCompactTdd(tdd)) {
                errors += "${file.name}: TDD cases must be a bullet list or state 'not applicable' with a reason"
            }
        } else {
            if ("## Goal" !in text && "**Goal:**" !in text) {
                errors += "${file.name}: missing Goal"
            }
            if (!hasTddEvidence(text)) {
                errors += "${file.name}: TDD cases must be a bullet list or state 'not applicable' with a reason"
            }
        }
        return errors
    }

    private fun isCompactAdkPlan(text: String): Boolean =
        "## Acceptance criteria" in text || "## Target claims" in text || "## Non-goals" in text

    private fun validCompactTdd(tdd: String): Boolean {
        if (tdd.isBlank()) return false
        val normalized = tdd.trim()
        if (normalized.contains("not applicable", ignoreCase = true)) {
            val marker = Regex("(?i)not\\s+applicable").find(normalized)!!
            val explanation = normalized.substring(marker.range.last + 1).trim(' ', ':', '-', '—', '–')
            if (explanation.isNotBlank()) return true
        }
        val bullets = normalized.lines().map(String::trim).filter { it.startsWith("-") }
        if (bullets.isEmpty()) return false
        return bullets.all { bullet ->
            val content = bullet.removePrefix("-").trim()
            if (content.isBlank()) return false
            if (content == "[ ]") return false
            val checklistContent = Regex("^\\[([^]]+)]\\s*(.*)$").matchEntire(content)
            if (checklistContent != null) checklistContent.groupValues[2].trim().isNotBlank()
            else content.isNotBlank()
        }
    }

    private fun hasTddEvidence(text: String): Boolean {
        val lines = text.lines()
        if (lines.any { it.contains("TDD", ignoreCase = true) && it.contains("not applicable", ignoreCase = true) }) return true
        if (lines.any { it.trim().startsWith("- [") }) return true
        val tddLine = lines.indexOfFirst { it.contains("TDD", ignoreCase = true) }
        if (tddLine < 0) return false
        return lines.drop(tddLine).take(16).any { it.trim().startsWith("-") }
    }
}
