package io.agentdevkit.planner.app

import io.agentdevkit.planner.PlanningDocument
import io.agentdevkit.planner.VerifiedPlan
import io.agentdevkit.scheduler.TaskSnapshot
import io.agentdevkit.scheduler.TaskState
import io.agentdevkit.scheduler.WriteScope
import io.agentdevkit.scheduler.Writes
import java.io.File
import java.nio.file.Paths

object PlanToSchedulerBridge {

    fun translate(
        plan: VerifiedPlan,
        root: File,
        states: Map<String, TaskState>,
    ): TaskSnapshot? {
        if (!plan.isCurrent(root)) return null
        return buildTaskSnapshot(plan.document, states[plan.document.id] ?: TaskState.PENDING, root)
    }

    /** Testable core: translates document fields without the isCurrent check. */
    internal fun buildTaskSnapshot(doc: PlanningDocument, state: TaskState, root: File? = null): TaskSnapshot {
        val writes = buildFileReservations(doc, root)
        return TaskSnapshot(
            id = doc.id,
            priority = doc.priority,
            state = state,
            dependencyIds = doc.dependencies,
            writes = writes,
        )
    }

    private fun buildFileReservations(doc: PlanningDocument, root: File?): Writes {
        if (doc.targetFiles.isEmpty()) return Writes.Unknown
        // The required declaration is the scheduling input. Include newly proposed step/test
        // files so an agent-generated addition cannot silently escape the reservation set.
        val files = (doc.targetFiles + doc.steps.flatMap { step -> step.targets.map { it.file } } +
            doc.verification.mapNotNull { it.target?.file }).distinct()
        return Writes.Known(files.map { WriteScope.FileScope(normaliseTarget(root, it)) })
    }

    private fun normaliseTarget(root: File?, path: String): String {
        require(path.isNotBlank() && !File(path).isAbsolute && !path.any(Char::isISOControl) &&
            ".." !in path.split('/', '\\')) { "Invalid target_files path: $path" }
        if (root == null) return normalisePath(path)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, path).canonicalFile
        require(target != canonicalRoot && target.toPath().startsWith(canonicalRoot.toPath()) &&
            (!target.exists() || target.isFile)) { "Target file outside project or not a file: $path" }
        return target.relativeTo(canonicalRoot).invariantSeparatorsPath
    }

    internal fun targetFileScope(path: String, root: File? = null): WriteScope.FileScope =
        WriteScope.FileScope(normaliseTarget(root, path))

    internal fun parseWriteScope(raw: String): WriteScope = when {
        raw.startsWith("file:") -> WriteScope.FileScope(normalisePath(raw.removePrefix("file:")))
        raw.startsWith("dir:") -> WriteScope.DirScope(normalisePath(raw.removePrefix("dir:")))
        raw.startsWith("resource:") -> WriteScope.ResourceScope(raw.removePrefix("resource:"))
        else -> throw IllegalArgumentException("Unknown write-scope prefix in: $raw")
    }

    private fun normalisePath(path: String): String {
        // Resolve . and .. segments, strip trailing slash, use forward slashes.
        val normalised = Paths.get(path).normalize().toString().replace('\\', '/')
        val result = normalised.trimEnd('/')
        // Paths.get(".").normalize() returns "" on all JVMs; an empty or bare-dot path after
        // normalisation means "entire repository root", which is ambiguous and unenforceable.
        // Reject it explicitly rather than silently producing a DirScope("") that never matches.
        require(result != "" && result != ".") {
            "Write scope path resolves to the repository root ('$path' → '$result'). " +
                "Use an explicit subdirectory path or 'resource:' for a root-wide claim."
        }
        return result
    }
}
