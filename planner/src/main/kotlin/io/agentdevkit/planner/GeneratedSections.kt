package io.agentdevkit.planner

/** In-file provenance: untouched agent-authored sections may be replaced; human edits cannot. */
internal object GeneratedSections {
    private val names = linkedMapOf("AC" to "Acceptance criteria", "S" to "Changes", "T" to "Verification", "Q" to "Questions")
    private val marker = Regex("(?m)^<!-- adk:generated:v1 ((?:AC|S|T|Q)=[0-9a-f]{64}(?:;(?:AC|S|T|Q)=[0-9a-f]{64})*) -->\\r?\\n")
    private val frontmatter = Regex("\\A---[ \\t]*\\r?\\n.*?\\r?\\n---[ \\t]*\\r?\\n", RegexOption.DOT_MATCHES_ALL)

    private fun signatures(text: String): Map<String, String>? {
        val matches = marker.findAll(text).toList()
        if (matches.isEmpty()) {
            require("<!-- adk:generated:" !in text) { "Generated plan marker was edited" }
            return null
        }
        require(matches.size == 1) { "Expected exactly one generated plan marker" }
        val pairs = matches.single().groupValues[1].split(';').map { it.substringBefore('=') to it.substringAfter('=') }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Generated section IDs must be unique" }
        return pairs.toMap()
    }

    fun strip(text: String): String {
        signatures(text)
        return marker.replace(text, "")
    }

    private fun body(text: String, name: String): String? = MarkdownSections.scan(
        strip(ReviewProjection.authoredBytes(text).toString(Charsets.UTF_8)))
        .firstOrNull { it.level == 2 && it.heading == name }?.body?.trim()?.let { section ->
            if (name == "Questions") section.replace(Regex("(?m)^(\\*\\*Answer:\\*\\*)[^\\r\\n]*$"), "$1") else section
        }?.takeIf { it.isNotBlank() }

    fun owned(text: String): Set<String> = signatures(text)?.keys.orEmpty()

    fun problem(text: String): String? = try {
        signatures(text)?.entries?.firstOrNull { (key, expected) ->
            val name = names[key] ?: error("Unknown generated section $key")
            val actual = body(text, name) ?: ""
            hash(actual.toByteArray(Charsets.UTF_8)) != expected
        }?.let { "Generated ${names.getValue(it.key)} section was edited; resolve its human changes before automated revision" }
    } catch (error: IllegalArgumentException) { error.message.orEmpty() }

    fun mark(original: String, proposal: String): String {
        val previouslyGenerated = owned(original)
        val generated = names.mapNotNull { (key, section) ->
            val body = body(proposal, section) ?: return@mapNotNull null
            if (key in previouslyGenerated || body(original, section) == null) "$key=${hash(body.toByteArray(Charsets.UTF_8))}" else null
        }
        if (generated.isEmpty()) return proposal
        val prefix = frontmatter.find(proposal)?.value ?: error("Cannot mark generated sections without closed frontmatter")
        val newline = if (prefix.endsWith("\r\n")) "\r\n" else "\n"
        return prefix + "<!-- adk:generated:v1 ${generated.joinToString(";")} -->$newline" + proposal.removePrefix(prefix)
    }

    private data class Replacement(val start: Int, val end: Int, val text: String)

    /** Protect existing answers and keep human decisions unresolved; edit only answer spans, never line endings. */
    fun preserveAnswers(original: PlanningDocument, proposal: String): String {
        val parsed = PlanningDocumentReader.parse(proposal) as? DocumentRead.Valid
            ?: throw IllegalArgumentException("Agent plan with human answers is not a valid document")
        val answered = original.questions.filter { !it.answer.isNullOrBlank() }
        val protectedExisting = original.questions.filter { it.resolveThrough == QuestionResolution.DECISION || !it.answer.isNullOrBlank() }
        for (question in protectedExisting) {
            val next = parsed.document.questions.firstOrNull { it.id == question.id }
                ?: throw IllegalArgumentException("Agent plan removed human decision or answered question ${question.id}")
            require(next.question == question.question && next.why == question.why && next.blocks == question.blocks &&
                next.resolveThrough == question.resolveThrough && next.investigation == question.investigation) {
                "Agent plan changed human decision or answered question ${question.id}"
            }
        }
        val protected = parsed.document.questions.filter { next -> next.resolveThrough == QuestionResolution.DECISION ||
            answered.any { it.id == next.id } }
        if (protected.isEmpty()) return proposal
        val blocks = MarkdownSections.scan(proposal)
        val offsets = buildList {
            add(0)
            proposal.forEachIndexed { index, character -> if (character == '\n') add(index + 1) }
        }
        val newline = if ("\r\n" in proposal) "\r\n" else "\n"
        val replacements = protected.map { question ->
            val heading = blocks.firstOrNull { it.level == 3 &&
                (it.heading.startsWith("${question.id} —") || it.heading.startsWith("${question.id} -")) }
                ?: throw IllegalArgumentException("Agent plan removed answered question ${question.id}")
            val start = offsets.getOrElse(heading.line) { proposal.length }
            val end = blocks.firstOrNull { it.line > heading.line && it.level <= 3 }
                ?.let { offsets.getOrElse(it.line - 1) { proposal.length } } ?: proposal.length
            val match = Regex("(?m)^\\*\\*Answer:\\*\\*[^\\r\\n]*(?:\\r?\\n|$)").find(proposal, start)
                ?.takeIf { it.range.first < end }
            val answer = original.questions.firstOrNull { it.id == question.id }?.answer.orEmpty()
            val line = if (answer.isBlank()) "**Answer:**" else "**Answer:** $answer"
            if (match == null) Replacement(end, end, (if (end > 0 && proposal[end - 1] != '\n') newline else "") + line + newline)
            else Replacement(match.range.first, match.range.last + 1,
                line + (if (match.value.endsWith("\r\n")) "\r\n" else if (match.value.endsWith("\n")) "\n" else ""))
        }
        return StringBuilder(proposal).also { result ->
            replacements.sortedByDescending { it.start }.forEach { result.replace(it.start, it.end, it.text) }
        }.toString()
    }
}
