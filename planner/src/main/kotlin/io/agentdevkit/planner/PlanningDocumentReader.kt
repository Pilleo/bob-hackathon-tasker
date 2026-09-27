package io.agentdevkit.planner

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.io.File
import java.util.Locale

object PlanningDocumentReader {
    private val yaml = ObjectMapper(YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
    private val heading = Regex("^(AC|S|T|Q)([1-9][0-9]*)[ \\t]+[—-][ \\t]+(.+)$")
    private val metadata = Regex("(?s)^\\s*```yaml\\s*\n(.*?)\n```(?:\\s*\n|$)")

    fun read(file: File, root: File): DocumentRead {
        val target = boundary({ return DocumentRead.Invalid(listOf(DocumentProblem("DOCUMENT_IO", file.path, 1, it.message.orEmpty()))) }) { file.canonicalFile }
        if (!target.toPath().startsWith(root.canonicalFile.toPath()) || !target.isFile || target.length() > 262_144) {
            return DocumentRead.Invalid(listOf(DocumentProblem("DOCUMENT_PATH", file.path, 1, "Document must be a bounded repository file")))
        }
        return boundary({ DocumentRead.Invalid(listOf(DocumentProblem("DOCUMENT_IO", file.path, 1, it.message.orEmpty()))) }) { parse(target.readText()) }
    }

    fun parse(text: String): DocumentRead {
        if (text.toByteArray().size > 262_144) return invalid("DOCUMENT_TOO_LARGE", "document", 1, "Document exceeds 256 KiB")
        val match = Regex("\\A---[ \\t]*\\r?\n(.*?)\r?\n---[ \\t]*\\r?\n(.*)\\z", RegexOption.DOT_MATCHES_ALL).matchEntire(text)
            ?: return invalid("FRONTMATTER_INVALID", "frontmatter", 1, "Document must start with closed YAML frontmatter")
        val frontmatter = try { parseYaml(match.groupValues[1]) }
            catch (error: Exception) { return invalid("YAML_DUPLICATE_KEY", "frontmatter", 1, "Malformed or duplicate YAML: ${error.message}") }
        if (!frontmatter.isObject) return invalid("FRONTMATTER_INVALID", "frontmatter", 1, "Frontmatter must be a mapping")
        val structuralIssues = mutableListOf<DocumentProblem>()
        val frontmatterLine = match.groupValues[1].lineSequence().count()
        if (frontmatterLine > 200) structuralIssues += DocumentProblem("FRONTMATTER_LIMIT", "frontmatter", 1, "Frontmatter exceeds 200 lines")
        val version = frontmatter.get("schema_version")
        if (version == null) return if (structuralIssues.isEmpty()) DocumentRead.Legacy(IssueDraft("legacy", string(frontmatter, "title").orEmpty(), "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), text)) else DocumentRead.Invalid(structuralIssues)
        if (!version.isInt || version.intValue() != 2) return invalid("UNKNOWN_SCHEMA_VERSION", "schema_version", 2, "Only schema_version: 2 is supported")
        val kind = when (string(frontmatter, "document_type")) {
            "issue" -> DocumentKind.ISSUE
            "execution_plan" -> DocumentKind.EXECUTION_PLAN
            else -> return invalid("DOCUMENT_TYPE_INVALID", "document_type", 2, "Use issue or execution_plan")
        }
        val problems = structuralIssues
        val allowed = setOf("schema_version", "document_type", "id", "title", "severity", "status", "priority", "dependencies", "target_files", "base_revision", "source_issue", "component")
        checkKeys(frontmatter, allowed, "frontmatter", 2, problems)
        if (!frontmatter.path("priority").isMissingNode && !frontmatter.path("priority").isInt) problems += DocumentProblem("YAML_TYPE", "frontmatter.priority", 2, "Expected integer")
        val blocks = MarkdownSections.scan(match.groupValues[2])
        blocks.filter { it.level == 3 && it.heading.matches(Regex("^[ASTQ][A-Z0-9]*[ \\t]+.*")) && heading.matchEntire(it.heading) == null }.forEach {
            problems += DocumentProblem("SECTION_ID_INVALID", it.heading, it.line, "Expected AC1, S1, T1 or Q1 followed by an em dash")
        }
        val groups = blocks.filter { it.level == 2 }.groupBy { it.heading }
        val knownGroups = setOf("Context", "Targets", "Acceptance criteria", "Changes", "Verification", "Questions", "Review notes", "Goal", "Non-goals", "Risks")
        groups.forEach { (name, values) ->
            if (values.size != 1) problems += DocumentProblem("SECTION_DUPLICATE", name, values[1].line, "Duplicate section $name")
            if (name !in knownGroups) problems += DocumentProblem("SECTION_UNKNOWN", name, values.first().line, "Unknown section $name")
        }
        fun sections(name: String, prefix: String) = blocks.filter { it.level == 3 && heading.matchEntire(it.heading)?.groupValues?.get(1) == prefix && blocks.any { parent -> parent.level == 2 && parent.heading == name && parent.line < it.line && (blocks.firstOrNull { next -> next.level == 2 && next.line > parent.line }?.line ?: Int.MAX_VALUE) > it.line } }
        val criteria = sections("Acceptance criteria", "AC").map { block ->
            Criterion(heading.matchEntire(block.heading)!!.groupValues.take(3).drop(1).joinToString(""), block.heading.substringAfter("—", block.heading.substringAfter("-")).trim(), block.body.trim())
        }
        val verifications = sections("Verification", "T").map { block ->
            val id = heading.matchEntire(block.heading)!!.let { it.groupValues[1] + it.groupValues[2] }
            val (meta, prose) = metadataFor(block, problems)
            checkKeys(meta, setOf("kind", "file", "symbol"), id, block.line, problems)
            VerificationRef(id, block.heading.substringAfter("—", block.heading.substringAfter("-")).trim(), prose,
                string(meta, "file")?.let { TargetRef(it, string(meta, "symbol"), targetKind(meta, id, block.line, problems)) })
        }
        val steps = sections("Changes", "S").map { block ->
            val id = heading.matchEntire(block.heading)!!.let { it.groupValues[1] + it.groupValues[2] }
            val (meta, prose) = metadataFor(block, problems)
            checkKeys(meta, setOf("acceptance", "targets", "verification", "verification_not_applicable"), id, block.line, problems)
            val targets = array(meta, "targets", id, block.line, problems).mapIndexedNotNull { n, node ->
                if (!node.isObject) { problems += DocumentProblem("TARGET_INVALID", "$id.targets[$n]", block.line, "Target must be a mapping"); null }
                else {
                    checkKeys(node, setOf("file", "symbol", "kind"), "$id.targets[$n]", block.line, problems)
                    string(node, "file")?.let { TargetRef(it, string(node, "symbol"), targetKind(node, id, block.line, problems)) }
                }
            }
            PlanStep(id, block.heading.substringAfter("—", block.heading.substringAfter("-")).trim(), prose,
                strings(meta, "acceptance", id, block.line, problems), targets,
                strings(meta, "verification", id, block.line, problems),
                string(meta, "verification_not_applicable"))
        }
        val questions = sections("Questions", "Q").map { block ->
            val id = heading.matchEntire(block.heading)!!.let { it.groupValues[1] + it.groupValues[2] }
            val (meta, prose) = metadataFor(block, problems)
            checkKeys(meta, setOf("blocks", "resolve_through"), id, block.line, problems)
            val blockers = strings(meta, "blocks", id, block.line, problems)
            if (blockers.isEmpty()) problems += DocumentProblem("QUESTION_BLOCKS", id, block.line, "Question must block at least one step")
            val resolution = when (string(meta, "resolve_through")) {
                "investigation" -> QuestionResolution.INVESTIGATION
                "experiment" -> QuestionResolution.EXPERIMENT
                "decision" -> QuestionResolution.DECISION
                else -> { problems += DocumentProblem("QUESTION_RESOLUTION", id, block.line, "Choose investigation, experiment or decision"); QuestionResolution.DECISION }
            }
            fun field(name: String): String? = Regex("(?m)^\\*\\*${Regex.escape(name)}:\\*\\*[ \\t]*(.*)$").find(prose)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
            if (resolution != QuestionResolution.DECISION && field("Investigation") == null) problems += DocumentProblem("QUESTION_ACTION", id, block.line, "Describe an investigation or experiment")
            PlanQuestion(id, field("Question").orEmpty(), field("Why it matters").orEmpty(), blockers, resolution, field("Investigation"), field("Answer"))
        }
        if (steps.size > 200 || questions.size > 200 || criteria.size > 200 || verifications.size > 200) problems += DocumentProblem("SECTION_LIMIT", "sections", 1, "Section count exceeds 200")
        steps.filter { it.targets.size > 32 }.forEach { problems += DocumentProblem("TARGET_LIMIT", "${it.id}.targets", 1, "Step target count exceeds 32") }
        for ((prefix, collection) in listOf("AC" to criteria.map { it.id }, "S" to steps.map { it.id }, "T" to verifications.map { it.id }, "Q" to questions.map { it.id })) {
            collection.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { problems += DocumentProblem("SECTION_DUPLICATE", it, 1, "Duplicate $prefix identifier $it") }
        }
        steps.forEach { step ->
            step.acceptance.filterNot { id -> criteria.any { it.id == id } }.forEach { problems += DocumentProblem("REFERENCE_NOT_FOUND", "${step.id}.acceptance", 1, "Unknown $it") }
            step.verification.filterNot { id -> verifications.any { it.id == id } }.forEach { problems += DocumentProblem("REFERENCE_NOT_FOUND", "${step.id}.verification", 1, "Unknown $it") }
        }
        questions.forEach { question -> question.blocks.filterNot { id -> steps.any { it.id == id } }.forEach {
            problems += DocumentProblem("REFERENCE_NOT_FOUND", "${question.id}.blocks", 1, "Unknown $it")
        } }
        val dependencies = strings(frontmatter, "dependencies", "frontmatter", 2, problems)
        val targetFiles = if (frontmatter.has("target_files")) strings(frontmatter, "target_files", "frontmatter", 2, problems)
            else groups["Targets"]?.firstOrNull()?.body?.lineSequence()?.map { it.trim().removePrefix("-").trim() }
                ?.filter { it.isNotBlank() && !it.startsWith("Optional.") }?.toList().orEmpty()
        if (targetFiles.size > 32 || targetFiles.size != targetFiles.distinct().size) {
            problems += DocumentProblem("TARGET_FILES_INVALID", "target_files", 2, "Use at most 32 distinct target files")
        }
        targetFiles.forEachIndexed { index, path ->
            if (path.isBlank() || File(path).isAbsolute || path.any(Char::isISOControl) ||
                ".." in path.split('/', '\\')) problems += DocumentProblem("TARGET_PATH_INVALID", "target_files[$index]", 2,
                    "Target must be a repository-relative file path")
        }
        if (problems.isNotEmpty()) return DocumentRead.Invalid(problems)
        return DocumentRead.Valid(PlanningDocument(kind, string(frontmatter, "id").orEmpty(), string(frontmatter, "title").orEmpty(), text,
            criteria, steps, verifications, questions, frontmatter.path("priority").takeIf { it.isInt }?.intValue() ?: 0,
            dependencies, string(frontmatter, "base_revision"), targetFiles, string(frontmatter, "status") ?: "open"))
    }

    private fun parseYaml(text: String): JsonNode {
        if (Regex("(?m)(?:^|[ \\t])[&*!]|<<[ \\t]*:").containsMatchIn(text)) error("YAML aliases, tags, and merges are not supported")
        return yaml.readTree(text) ?: error("Empty YAML")
    }

    private fun metadataFor(block: MarkdownBlock, problems: MutableList<DocumentProblem>): Pair<JsonNode, String> {
        val match = metadata.find(block.body)
        if (match == null) return yaml.createObjectNode() to block.body
        if (Regex("(?m)^```yaml[ \\t]*$").findAll(block.body).count() > 1) problems += DocumentProblem("METADATA_DUPLICATE", block.heading, block.line, "Only one YAML metadata block is allowed")
        val parsed = try { parseYaml(match.groupValues[1]) } catch (error: Exception) {
            problems += DocumentProblem("YAML_INVALID", block.heading, block.line, error.message.orEmpty()); yaml.createObjectNode()
        }
        return parsed to block.body.removeRange(match.range).trim()
    }

    private fun checkKeys(node: JsonNode, allowed: Set<String>, path: String, line: Int, problems: MutableList<DocumentProblem>) {
        if (!node.isObject) { problems += DocumentProblem("YAML_TYPE", path, line, "Expected mapping"); return }
        node.fieldNames().forEachRemaining { if (it !in allowed) problems += DocumentProblem("UNKNOWN_FIELD", "$path.$it", line, "Unknown field") }
    }

    private fun targetKind(node: JsonNode, path: String, line: Int, problems: MutableList<DocumentProblem>): TargetKind = when (string(node, "kind")) {
        "existing" -> TargetKind.EXISTING
        "new" -> TargetKind.NEW
        else -> { problems += DocumentProblem("TARGET_KIND", path, line, "Use existing or new"); TargetKind.EXISTING }
    }

    private fun string(node: JsonNode, key: String): String? = node.path(key).takeIf { it.isTextual }?.textValue()
    private fun array(node: JsonNode, key: String, path: String, line: Int, problems: MutableList<DocumentProblem>): List<JsonNode> {
        val value = node.path(key)
        if (value.isMissingNode) return emptyList()
        if (!value.isArray) { problems += DocumentProblem("YAML_TYPE", "$path.$key", line, "Expected array"); return emptyList() }
        return value.toList()
    }
    private fun strings(node: JsonNode, key: String, path: String, line: Int, problems: MutableList<DocumentProblem>): List<String> =
        array(node, key, path, line, problems).mapIndexedNotNull { index, value ->
            if (!value.isTextual) { problems += DocumentProblem("YAML_TYPE", "$path.$key[$index]", line, "Expected string"); null } else value.textValue()
        }
    private fun invalid(code: String, path: String, line: Int, message: String) = DocumentRead.Invalid(listOf(DocumentProblem(code, path, line, message)))
}
