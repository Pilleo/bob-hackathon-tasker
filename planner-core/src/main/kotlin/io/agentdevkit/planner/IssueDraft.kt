package io.agentdevkit.planner

data class IssueDraft(
    val id: String,
    val title: String,
    val context: String,
    val needed: List<String>,
    val acceptanceCriteria: List<String>,
    val targetFiles: List<String>,
    val targetSymbols: List<String>,
    val questions: List<String>,
    val content: String,
)

data class DraftReadResult(val draft: IssueDraft?, val errors: List<String>)
