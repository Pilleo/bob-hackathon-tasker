package io.agentdevkit.planner

import java.io.File

object PlanningEvidenceCollector {
    fun collect(document: PlanningDocument, root: File, tools: PlanningTools = UnavailablePlanningTools): PlanningEvidence = boundary(
        { PlanningEvidence(PlanningStatus.FAILED, emptyList(), emptyList(), listOf(PlanningIssue("EVIDENCE_IO_FAILED", it.message.orEmpty())), null) },
    ) {
        val revision = boundary({ null }) { tools.revision(root) }
        val issues = mutableListOf<PlanningIssue>()
        if (revision == null) issues += PlanningIssue("REVISION_UNAVAILABLE", "Repository revision unavailable")
        val sources = mutableMapOf<TargetRef, SourceEvidence>()
        val impacts = mutableMapOf<TargetRef, ImpactEvidence>()
        val files = linkedMapOf<String, FileFingerprint>()
        val rootPath = root.canonicalFile.toPath()
        fun verify(target: TargetRef): Pair<TargetEvidence, List<PlanningIssue>> {
            val name = target.file
            if (name.isBlank() || File(name).isAbsolute || name.split('/', '\\').contains(".."))
                return TargetEvidence(name, target.symbol, "INVALID") to listOf(PlanningIssue("TARGET_PATH_INVALID", "Invalid target: $name"))
            val file = File(root, name)
            if (!file.canonicalFile.toPath().startsWith(rootPath))
                return TargetEvidence(name, target.symbol, "INVALID") to listOf(PlanningIssue("TARGET_OUTSIDE_REPOSITORY", "Target escapes repository: $name"))
            if (!file.exists()) {
                files[name] = FileFingerprint(name, "MISSING")
                return if (target.kind == TargetKind.NEW) TargetEvidence(name, target.symbol, "PROPOSED") to emptyList()
                else TargetEvidence(name, target.symbol, "UNAVAILABLE") to listOf(PlanningIssue("EXISTING_TARGET_MISSING", "Existing target is missing: $name"))
            }
            if (!file.isFile || file.length() > 1_048_576)
                return TargetEvidence(name, target.symbol, "UNAVAILABLE") to listOf(PlanningIssue("TARGET_UNREADABLE", "Target is not a bounded file: $name"))
            files[name] = FileFingerprint(name, hash(file.readBytes()))
            if (target.kind == TargetKind.NEW) return TargetEvidence(name, target.symbol, "INVALID") to
                listOf(PlanningIssue("TARGET_KIND_MISMATCH", "Target marked new already exists: $name"))
            val result = sources.getOrPut(target) { boundary({ SourceEvidence(PlanningStatus.UNAVAILABLE, "UNAVAILABLE", issues = listOf(PlanningIssue("AST_FAILED", it.message.orEmpty()))) }) {
                tools.source(root, target)
            } }
            return TargetEvidence(name, target.symbol, result.trust, result.excerpt.take(1500)) to result.issues +
                (if (result.status == PlanningStatus.OK) emptyList() else listOf(PlanningIssue("AST_INCOMPLETE", "Target evidence incomplete for $name::${target.symbol}")))
        }
        val steps = document.steps.take(200).map { step ->
            val stepIssues = mutableListOf<PlanningIssue>()
            val targets = step.targets.take(32).map { target -> verify(target).also { stepIssues += it.second }.first }
            val verification = step.verification.take(32).mapNotNull { id -> document.verification.firstOrNull { it.id == id }?.target }.map { target ->
                verify(target).also { stepIssues += it.second }.first
            }
            val results = step.targets.filter { it.kind == TargetKind.EXISTING && it.symbol != null }.take(32).map { target ->
                val source = sources[target]
                val result = if (source?.status == PlanningStatus.OK && source.trust == "PARSER_VERIFIED") impacts.getOrPut(target) {
                    boundary({ ImpactEvidence(PlanningStatus.UNAVAILABLE, issues = listOf(PlanningIssue("CODANNA_FAILED", it.message.orEmpty()))) }) { tools.impact(root, target) }
                } else ImpactEvidence(PlanningStatus.UNAVAILABLE, issues = listOf(PlanningIssue("IMPACT_UNQUALIFIED", "Target not uniquely verified: ${target.file}::${target.symbol}")))
                stepIssues += result.issues
                if (result.status != PlanningStatus.OK || result.symbols.isEmpty() || result.symbols == listOf(target.symbol)) {
                    stepIssues += PlanningIssue("IMPACT_INCOMPLETE", "Impact for ${target.file}::${target.symbol} is not a verified caller graph")
                }
                result.copy(symbols = result.symbols.take(100))
            }
            StepEvidence(step.id, targets, verification, results, stepIssues)
        }
        issues += steps.flatMap { it.issues }
        if (!files.values.all { it.matches(root) }) issues += PlanningIssue("EVIDENCE_CHANGED", "Source changed during collection")
        PlanningEvidence(if (issues.isEmpty()) PlanningStatus.OK else PlanningStatus.PARTIAL, steps.flatMap { it.targets }, files.values.toList(),
            issues, revision, steps = steps)
    }

    fun collect(draft: IssueDraft, root: File, tools: PlanningTools = UnavailablePlanningTools): PlanningEvidence = boundary(
        { PlanningEvidence(PlanningStatus.FAILED, emptyList(), emptyList(), listOf(PlanningIssue("EVIDENCE_IO_FAILED", it.message.orEmpty())), null) },
    ) {
        val targets = mutableListOf<TargetEvidence>()
        val fingerprints = linkedMapOf<String, FileFingerprint>()
        val issues = mutableListOf<PlanningIssue>()
        val impacts = linkedMapOf<String, ImpactEvidence>()
        var degraded = false
        val revision = boundary({ issues += PlanningIssue("REVISION_UNAVAILABLE", it.message.orEmpty()); null }) { tools.revision(root) }
        if (revision == null && issues.none { it.code == "REVISION_UNAVAILABLE" }) issues += PlanningIssue("REVISION_UNAVAILABLE", "No repository revision available")
        if (draft.targetFiles.any { File(it).isAbsolute || it.split('/', '\\').any { part -> part == ".." } || it.isBlank() }) {
            return@boundary PlanningEvidence(PlanningStatus.INVALID, emptyList(), emptyList(),
                listOf(PlanningIssue("TARGET_PATH_INVALID", "Targets must be repository-relative paths")), revision)
        }
        val canonicalRoot = root.canonicalFile.toPath()
        for (path in draft.targetFiles.distinct().take(32)) {
            val file = File(root, path)
            if (!file.canonicalFile.toPath().startsWith(canonicalRoot)) {
                return@boundary PlanningEvidence(PlanningStatus.INVALID, emptyList(), emptyList(),
                    listOf(PlanningIssue("TARGET_OUTSIDE_REPOSITORY", "Target is outside repository: $path")), revision)
            }
            if (!file.exists()) {
                targets += TargetEvidence(path, null, "PROPOSED")
                fingerprints[path] = FileFingerprint(path, "MISSING")
                degraded = true
                continue
            }
            if (!file.isFile || file.length() > 1_048_576) {
                issues += PlanningIssue("TARGET_UNREADABLE", "Not a regular bounded source file: $path")
                degraded = true
                continue
            }
            fingerprints[path] = FileFingerprint(path, hash(file.readBytes()))
            val symbols: List<String?> = draft.targetSymbols.distinct().take(16).ifEmpty { listOf(null) }
            for (symbol in symbols) {
                val result = boundary({ SourceEvidence(PlanningStatus.UNAVAILABLE, "UNAVAILABLE", issues = listOf(PlanningIssue("AST_FAILED", it.message.orEmpty()))) }) {
                    tools.source(root, path, symbol)
                }
                targets += TargetEvidence(path, symbol, result.trust, result.excerpt.take(1500))
                issues += result.issues
                if (result.excerpt.length > 1500) issues += PlanningIssue("SOURCE_TRUNCATED", "Source excerpt truncated: $path")
                if (result.status != PlanningStatus.OK) degraded = true
            }
        }
        for (symbol in draft.targetSymbols.distinct().take(16)) {
            val result = boundary({ ImpactEvidence(PlanningStatus.UNAVAILABLE, issues = listOf(PlanningIssue("CODANNA_FAILED", it.message.orEmpty()))) }) {
                tools.impact(root, symbol)
            }
            impacts[symbol] = result.copy(symbols = result.symbols.take(100))
            issues += result.issues
            if (result.symbols.size > 100) issues += PlanningIssue("IMPACT_TRUNCATED", "Impact exceeds 100 symbols for $symbol")
            if (result.status != PlanningStatus.OK) degraded = true
            if (result.symbols.isEmpty() || result.symbols == listOf(symbol)) {
                issues += PlanningIssue("IMPACT_INCOMPLETE", "Impact for $symbol is empty or self-only; not proof of no callers")
                degraded = true
            }
        }
        val tests = root.walkTopDown().onEnter { it == root || (!it.name.startsWith('.') && it.name !in setOf("build", "node_modules") && !java.nio.file.Files.isSymbolicLink(it.toPath())) }
            .maxDepth(12).filter { it.isFile && (it.path.contains("/test/") || it.path.contains("/tests/")) }.take(2000).toList()
        for (path in draft.targetFiles.distinct().take(32)) {
            if (path.contains("/test/") || path.contains("/tests/")) continue
            val candidates = tests.filter { it.nameWithoutExtension.startsWith(File(path).nameWithoutExtension) }.take(4)
            if (candidates.isEmpty()) issues += PlanningIssue("RELATED_TESTS_UNKNOWN", "No test candidate for $path; not proof of no tests")
            candidates.forEach { test ->
                if (test.canonicalFile.toPath().startsWith(canonicalRoot) && test.length() <= 1_048_576) {
                    val relative = test.relativeTo(root).invariantSeparatorsPath
                    fingerprints[relative] = FileFingerprint(relative, hash(test.readBytes()))
                    targets += TargetEvidence(relative, null, "TEST_CANDIDATE")
                }
            }
        }
        if (draft.targetFiles.size > 32 || draft.targetSymbols.size > 16 || tests.size == 2000) issues += PlanningIssue("PLANNING_TARGETS_TRUNCATED", "Collection budget reached")
        if (draft.targetFiles.isEmpty()) issues += PlanningIssue("PLANNING_TARGETS_UNKNOWN", "No file targets; impact is incomplete")
        if (!fingerprints.values.all { it.matches(root) }) return@boundary PlanningEvidence(PlanningStatus.INVALID, targets, fingerprints.values.toList(),
            issues + PlanningIssue("EVIDENCE_CHANGED", "Source changed while gathering evidence"), revision, impacts)
        PlanningEvidence(if (degraded || issues.isNotEmpty()) PlanningStatus.PARTIAL else PlanningStatus.OK,
            targets, fingerprints.values.toList(), issues, revision, impacts)
    }
}
