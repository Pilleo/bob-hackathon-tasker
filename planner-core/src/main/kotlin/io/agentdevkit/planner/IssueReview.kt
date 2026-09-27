package io.agentdevkit.planner

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ReviewVerdict { ACCEPT, CHANGES_REQUESTED, NEEDS_CLARIFICATION }

@Serializable
data class ReviewFinding(
    val id: String,
    val category: String,
    val severity: String,
    val blocking: Boolean,
    val detail: String,
    val resolution: String,
    val evidence: List<String>,
)

@Serializable
data class ReviewQuestion(val question: String, val blocks: String, val kind: String)

@Serializable
data class IssueReview(
    val verdict: ReviewVerdict,
    val summary: String,
    val findings: List<ReviewFinding>,
    val questions: List<ReviewQuestion>,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = false }

        fun parse(text: String): ParsedReview {
            val trimmed = text.trim()
            val payload = if (trimmed.startsWith("```json\n") && trimmed.endsWith("\n```")) trimmed.removePrefix("```json\n").removeSuffix("\n```").trim() else trimmed
            val candidate = runCatching {
                val root = json.parseToJsonElement(payload).jsonObject
                val findings = (root["findings"] as? JsonArray)?.map { element ->
                    val fields = element.jsonObject
                    val citation = fields["evidence"]
                    if (citation is JsonPrimitive && citation.isString) buildJsonObject {
                        fields.forEach { (key, value) -> put(key, if (key == "evidence") JsonArray(listOf(citation)) else value) }
                    } else fields
                }
                val normalized = if (findings != null) buildJsonObject {
                    root.forEach { (key, value) -> put(key, if (key == "findings") JsonArray(findings) else value) }
                } else root
                val questions = (normalized["questions"] as? JsonArray)?.map { element ->
                    val fields = element.jsonObject
                    val blocks = fields["blocks"]
                    if (blocks is JsonArray) {
                        require(blocks.isNotEmpty() && blocks.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() }) {
                            "Question blocks must be nonempty strings"
                        }
                        buildJsonObject {
                            fields.forEach { (key, value) -> put(key, if (key == "blocks")
                                JsonPrimitive(blocks.joinToString(", ") { (it as JsonPrimitive).content }) else value) }
                        }
                    } else if (blocks is JsonPrimitive && !blocks.isString && blocks.content in setOf("true", "false")) buildJsonObject {
                        fields.forEach { (key, value) -> put(key, if (key == "blocks") JsonPrimitive(if (blocks.content == "true") "Answer required before implementation" else "None") else value) }
                    } else fields
                }
                val finalPayload = if (questions != null) buildJsonObject {
                    normalized.forEach { (key, value) -> put(key, if (key == "questions") JsonArray(questions) else value) }
                } else normalized
                json.decodeFromString<IssueReview>(finalPayload.toString())
            }
                .getOrElse { return ParsedReview(null, listOf("Invalid review JSON: ${it.message}")) }
            val errors = buildList {
                if (candidate.summary.isBlank()) add("Review summary is required")
                if (candidate.findings.map { it.id }.distinct().size != candidate.findings.size) add("Finding IDs must be unique")
                if (candidate.findings.any { it.id.isBlank() || it.detail.isBlank() || it.resolution.isBlank() }) add("Findings require ID, detail, and resolution")
                if (candidate.questions.any { it.question.isBlank() || it.blocks.isBlank() }) add("Questions must state what they block")
                if (candidate.verdict == ReviewVerdict.ACCEPT && (candidate.findings.any { it.blocking } || candidate.questions.isNotEmpty())) add("ACCEPT cannot have blocking findings or questions")
            }
            return ParsedReview(candidate.takeIf { errors.isEmpty() }, errors)
        }
    }
}

data class ParsedReview(val review: IssueReview?, val errors: List<String>)
