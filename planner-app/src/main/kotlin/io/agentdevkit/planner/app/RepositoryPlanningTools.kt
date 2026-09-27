package io.agentdevkit.planner.app

import io.agentdevkit.planner.*
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull

private data class AstDeclaration(val start: Int, val end: Int, val text: String)

/** Portable read-only AST, repository revision, and path-qualified impact adapter. */
class RepositoryPlanningTools(
    private val budgetMs: Long = 45_000,
    private val clock: () -> Long = System::nanoTime,
    private val run: (List<String>, File, Long) -> CommandResult = CliToolRunner::run,
) : PlanningTools {
    init { require(budgetMs in 1..120_000) { "Evidence budget must be between 1 and 120 seconds" } }
    private val deadline = clock() + TimeUnit.MILLISECONDS.toNanos(budgetMs)

    override fun freshCollection(): PlanningTools = RepositoryPlanningTools(budgetMs, clock, run)

    private fun execute(arguments: List<String>, root: File): CommandResult {
        val remaining = deadline - clock()
        if (remaining <= 0) return CommandResult(null, "", "Evidence collection timed out")
        return run(arguments, root, minOf(15_000, maxOf(1, TimeUnit.NANOSECONDS.toMillis(remaining))))
    }

    override fun revision(root: File): String? = execute(listOf("git", "rev-parse", "HEAD"), root)
        .takeIf { it.exitCode == 0 }?.stdout?.trim()?.takeIf { it.matches(Regex("[0-9a-f]{40}")) }

    override fun source(root: File, path: String, symbol: String?): SourceEvidence = source(root,
        TargetRef(path, symbol, TargetKind.EXISTING))

    override fun source(root: File, target: TargetRef): SourceEvidence {
        fun gap(code: String, description: String) = SourceEvidence(PlanningStatus.UNAVAILABLE, "UNAVAILABLE",
            issues = listOf(PlanningIssue(code, description)), provider = "ast-grep")
        val path = target.file
        if (path.isBlank() || File(path).isAbsolute || path.split('/', '\\').contains("..")) return gap("TARGET_PATH_INVALID", path)
        val file = try { File(root, path).canonicalFile } catch (error: Exception) { return gap("TARGET_PATH_INVALID", error.message.orEmpty()) }
        if (!file.toPath().startsWith(root.canonicalFile.toPath()) || !file.isFile || file.length() > 1_048_576) {
            return gap("TARGET_UNREADABLE", "Not a bounded repository file: $path")
        }
        val name = target.symbol ?: return gap("SYMBOL_REQUIRED", "Name the declaration in $path for exact AST verification")
        if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return gap("SYMBOL_INVALID", "Unsupported declaration name: $name")
        val language = when (file.extension) { "kt", "kts" -> "kotlin"; "java" -> "java"; else -> return gap("LANGUAGE_UNSUPPORTED", path) }
        val patterns = if (language == "kotlin") listOf("fun \$NAME", "class \$NAME", "interface \$NAME", "object \$NAME")
            else listOf("class \$NAME", "interface \$NAME", "\$TYPE \$NAME(\$\$\$ARGS) { \$\$\$BODY }")
        return try {
            val matches = patterns.flatMap { pattern ->
                val response = execute(listOf("ast-grep", "run", "--pattern", pattern, "--lang", language, "--json=compact", path), root)
                if (response.exitCode == 1 && response.failure.isNullOrBlank() && response.stdout.trim() == "[]") return@flatMap emptyList()
                if (response.exitCode != 0 || response.failure != null) return gap("AST_UNAVAILABLE", response.failure ?: "ast-grep failed (exit ${response.exitCode})")
                Json.parseToJsonElement(response.stdout).jsonArray.mapNotNull { match ->
                    val node = match.jsonObject
                    val reported = node["file"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    if (File(root, reported).canonicalFile != file) return@mapNotNull null
                    val found = node["metaVariables"]?.jsonObject?.get("single")?.jsonObject?.get("NAME")?.jsonObject?.get("text")?.jsonPrimitive?.content
                    val offset = node["range"]?.jsonObject?.get("byteOffset")?.jsonObject
                    val start = offset?.get("start")?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    val end = offset["end"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                    if (found == name && start >= 0 && end > start && end <= file.length()) {
                        node["text"]?.jsonPrimitive?.content?.let { AstDeclaration(start, end, it) }
                    } else null
                }
            }.distinctBy { it.start to it.end }
            when (matches.size) {
                1 -> SourceEvidence(PlanningStatus.OK, "PARSER_VERIFIED", matches.single().text.take(1_200), provider = "ast-grep")
                0 -> gap("TARGET_SYMBOL_NOT_FOUND", "No declaration $name in $path")
                else -> gap("TARGET_AMBIGUOUS", "${matches.size} declarations named $name in $path")
            }
        } catch (error: CancellationException) { throw error }
          catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
          catch (error: Exception) { gap("AST_INVALID", error.message.orEmpty()) }
    }

    override fun impact(root: File, symbol: String) = ImpactEvidence(PlanningStatus.UNAVAILABLE,
        issues = listOf(PlanningIssue("IMPACT_UNQUALIFIED", "Name a file as well as $symbol")), provider = "codanna")

    override fun impact(root: File, target: TargetRef): ImpactEvidence {
        fun gap(code: String, message: String) = ImpactEvidence(PlanningStatus.UNAVAILABLE,
            issues = listOf(PlanningIssue(code, message)), provider = "codanna")
        val symbol = target.symbol ?: return gap("TARGET_UNQUALIFIED", "No symbol named for ${target.file}")
        return try {
            val search = execute(listOf("codanna", "mcp", "--json", "search_symbols", "query:$symbol", "limit:50"), root)
            if (search.exitCode != 0) return gap("CODANNA_UNAVAILABLE", search.failure ?: "Symbol search failed")
            val ids = Json.parseToJsonElement(search.stdout).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                val found = item.jsonObject["symbol"]?.jsonObject ?: return@mapNotNull null
                if (found["name"]?.jsonPrimitive?.content == symbol && found["file_path"]?.jsonPrimitive?.content == target.file)
                    found["id"]?.jsonPrimitive?.content?.toLongOrNull() else null
            }.distinct()
            if (ids.size != 1) return gap(if (ids.isEmpty()) "TARGET_NOT_INDEXED" else "TARGET_AMBIGUOUS",
                "Expected one indexed $symbol in ${target.file}; found ${ids.size}")
            val impact = execute(listOf("codanna", "mcp", "--json", "analyze_impact", "symbol_id:${ids.single()}"), root)
            if (impact.exitCode != 0) return gap("CODANNA_UNAVAILABLE", impact.failure ?: "Impact lookup failed")
            val names = Json.parseToJsonElement(impact.stdout).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            }
            ImpactEvidence(PlanningStatus.OK, (listOf(symbol) + names).distinct().take(100), provider = "codanna")
        } catch (error: CancellationException) { throw error }
          catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
          catch (error: Exception) { gap("CODANNA_INVALID", error.message.orEmpty()) }
    }

    override fun candidates(root: File, description: String): CandidateSearch {
        if (description.isBlank()) return CandidateSearch(PlanningStatus.INVALID,
            issues = listOf(PlanningIssue("DESCRIPTION_MISSING", "Describe the desired change")))
        return try {
            val result = execute(listOf("codanna", "mcp", "--json", "semantic_search_docs", "query:${description.take(2_000)}", "limit:5"), root)
            if (result.exitCode != 0) return CandidateSearch(PlanningStatus.UNAVAILABLE,
                issues = listOf(PlanningIssue("CODANNA_UNAVAILABLE", result.failure ?: "Candidate search failed")))
            val issues = mutableListOf<PlanningIssue>()
            val verified = Json.parseToJsonElement(result.stdout).jsonObject["data"]?.jsonArray.orEmpty().take(5).mapNotNull { item ->
                val found = item.jsonObject["symbol"]?.jsonObject ?: return@mapNotNull null
                val path = found["file_path"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val symbol = found["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val target = TargetRef(path, symbol, TargetKind.EXISTING)
                val evidence = source(root, target)
                if (evidence.status == PlanningStatus.OK && evidence.trust == "PARSER_VERIFIED") PlanningCandidate(target, evidence.excerpt)
                else { issues += evidence.issues; null }
            }.distinctBy { it.target }
            CandidateSearch(if (verified.isNotEmpty() && issues.isEmpty()) PlanningStatus.OK
                else if (verified.isNotEmpty()) PlanningStatus.PARTIAL else PlanningStatus.UNAVAILABLE, verified,
                issues.ifEmpty { if (verified.isEmpty()) listOf(PlanningIssue("TARGET_NOT_FOUND", "No parser-verified candidates")) else emptyList() })
        } catch (error: CancellationException) { throw error }
          catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw error }
          catch (error: Exception) { CandidateSearch(PlanningStatus.UNAVAILABLE,
              issues = listOf(PlanningIssue("DISCOVERY_INVALID", error.message.orEmpty()))) }
    }
}
