package io.agentdevkit.planner.app

import io.agentdevkit.scheduler.*
import io.agentdevkit.planner.DocumentKind
import io.agentdevkit.planner.DocumentRead
import io.agentdevkit.planner.PlanningDocumentReader
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.serialization.json.*

object ScheduleCommand {

    // Fixture JSON format: array of plain task objects.
    // Each object has: id, priority, state, dependencyIds, writes (string array or null).
    private data class TaskFixture(
        val id: String,
        val priority: Int,
        val state: String,
        val dependencyIds: List<String>,
        val targetFiles: List<String>,
    )

    fun run(args: List<String>, root: File = File(".").canonicalFile): PlannerCommandResult {
        val json = Json { prettyPrint = true }
        return try {
            val input = parseArgs(args)
            val fixture = input.fixturePath != null
            val parsed = input.fixturePath?.let { parseFixtureDocument(json, File(it).readText()) }
            val effectiveCapacity = parsed?.capacity ?: input.capacity
            val tasks = parsed?.fixtures?.map { it.toTaskSnapshot() }
                ?: input.issues.map { issue -> issueSnapshot(root, issue) }
            val result = BatchSelector.select(SchedulerInput(tasks, effectiveCapacity, parsed?.affinityGroups.orEmpty()))
            val output = json.encodeToString(JsonElement.serializer(), serialiseResult(result, fixture))
            // InvalidSnapshot means the caller supplied a broken snapshot; exit 1 so
            // automation can distinguish it from a usable batch (exit 0).
            val exitCode = if (result is ScheduleResult.InvalidSnapshot) 1 else 0
            PlannerCommandResult(exitCode, output)
        } catch (e: Exception) {
            PlannerCommandResult(4, "schedule: ${e.message}")
        }
    }

    private data class ScheduleArgs(val capacity: Int, val fixturePath: String?, val issues: List<String>)

    private fun parseArgs(args: List<String>): ScheduleArgs {
        var capacity = 2
        var fixturePath: String? = null
        val issues = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--capacity" -> {
                    capacity = args.getOrNull(++i)?.toIntOrNull()
                        ?: error("--capacity requires an integer argument")
                }
                "--fixture" -> {
                    fixturePath = args.getOrNull(++i)
                        ?: error("--fixture requires a path argument")
                }
                "--issues" -> issues += args.getOrNull(++i)
                    ?: error("--issues requires a repository-relative issue path")
                else -> error("Unknown argument: ${args[i]}")
            }
            i++
        }
        require((fixturePath == null) != issues.isEmpty()) { "Use either --fixture <path> or --issues <file> [--issues <file>]..." }
        return ScheduleArgs(capacity, fixturePath, issues)
    }

    private fun issueSnapshot(root: File, relative: String): TaskSnapshot {
        require(relative.isNotBlank() && !File(relative).isAbsolute && !relative.any(Char::isISOControl)) {
            "Issue path must be repository-relative: $relative"
        }
        val file = File(root, relative)
        val canonical = file.canonicalFile
        val backlog = File(root, "docs/internals/backlog").canonicalFile
        require(!Files.isSymbolicLink(file.toPath()) && canonical.toPath().startsWith(backlog.toPath()) &&
            canonical.isFile && canonical.length() <= 262_144 && canonical.name.startsWith("issue-") && canonical.extension == "md") {
            "Choose a bounded issue file inside docs/internals/backlog: $relative"
        }
        val read = PlanningDocumentReader.read(canonical, root)
        val document = (read as? DocumentRead.Valid)?.document
            ?: error("Invalid schema-v2 issue: $relative")
        require(document.kind == DocumentKind.ISSUE && document.id.isNotBlank() && document.targetFiles.isNotEmpty()) {
            "Issue ${document.id.ifBlank { relative }} must declare nonempty target_files"
        }
        val state = when (document.status.lowercase(Locale.ROOT)) {
            "open", "pending", "draft" -> TaskState.PENDING
            "running", "in_progress" -> TaskState.RUNNING
            "resolved", "closed", "succeeded" -> TaskState.SUCCEEDED
            "failed" -> TaskState.FAILED
            "blocked" -> TaskState.BLOCKED
            else -> error("Issue ${document.id} has unsupported status: ${document.status}")
        }
        return PlanToSchedulerBridge.buildTaskSnapshot(document, state, root)
    }

    private data class FixtureDocument(
        val fixtures: List<TaskFixture>,
        val capacity: Int?,
        val affinityGroups: List<AffinityGroup>,
    )

    private fun parseFixtureDocument(json: Json, raw: String): FixtureDocument {
        return when (val root = json.parseToJsonElement(raw)) {
            is JsonArray -> FixtureDocument(
                fixtures = parseTaskArray(root),
                capacity = null,
                affinityGroups = emptyList(),
            )
            is JsonObject -> FixtureDocument(
                fixtures = parseTaskArray(root["tasks"]!!.jsonArray),
                capacity = root["capacity"]?.jsonPrimitive?.intOrNull,
                affinityGroups = root["affinityGroups"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonArray
                    ?.map { parseAffinityGroup(it.jsonObject) }
                    ?: emptyList(),
            )
            else -> error("Fixture must be a JSON array or object")
        }
    }

    private fun parseTaskArray(array: JsonArray): List<TaskFixture> =
        array.map { elem ->
            val obj = elem.jsonObject
            TaskFixture(
                id = obj["id"]!!.jsonPrimitive.content,
                priority = obj["priority"]!!.jsonPrimitive.int,
                state = obj["state"]!!.jsonPrimitive.content,
                dependencyIds = obj["dependencyIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                targetFiles = (obj["target_files"] ?: error("target_files is required for ${obj["id"]}"))
                    .jsonArray.map { it.jsonPrimitive.content }.also { require(it.isNotEmpty()) { "target_files must be nonempty" } },
            )
        }

    private fun parseAffinityGroup(obj: JsonObject): AffinityGroup {
        val id = obj["id"]!!.jsonPrimitive.content
        val scopes = obj["scopes"]!!.jsonArray
            .map { PlanToSchedulerBridge.parseWriteScope(it.jsonPrimitive.content) }
            .toSet()
        return AffinityGroup(id, scopes)
    }

    private fun TaskFixture.toTaskSnapshot(): TaskSnapshot {
        val taskState = TaskState.valueOf(state)
        val writesValue = Writes.Known(targetFiles.map { PlanToSchedulerBridge.targetFileScope(it) })
        return TaskSnapshot(
            id = id,
            priority = priority,
            state = taskState,
            dependencyIds = dependencyIds,
            writes = writesValue,
        )
    }

    // ---------- JSON serialisation of ScheduleResult ----------

    private fun serialiseResult(result: ScheduleResult, fixture: Boolean): JsonElement = when (result) {
        is ScheduleResult.Batch -> buildJsonObject {
            put("type", "batch")
            put("fixture", fixture)
            putJsonArray("selected") { result.selected.forEach { add(it) } }
            putJsonArray("skipped") { result.skipped.forEach { add(serialiseSkipped(it)) } }
        }
        is ScheduleResult.InvalidSnapshot -> buildJsonObject {
            put("type", "invalidSnapshot")
            put("fixture", fixture)
            putJsonArray("diagnostics") { result.diagnostics.forEach { add(it) } }
        }
    }

    private fun serialiseSkipped(skipped: SkippedTask): JsonElement = buildJsonObject {
        put("id", skipped.id)
        put("reason", serialiseReason(skipped.reason))
    }

    private fun serialiseReason(reason: SkipReason): JsonElement = when (reason) {
        is SkipReason.UnknownWrites -> buildJsonObject {
            put("type", "UnknownWrites")
        }
        is SkipReason.UnknownRunningWrites -> buildJsonObject {
            put("type", "UnknownRunningWrites")
            put("taskId", reason.taskId)
        }
        is SkipReason.DependencyNotSucceeded -> buildJsonObject {
            put("type", "DependencyNotSucceeded")
            putJsonArray("unsatisfied") {
                reason.unsatisfied.forEach { (id, state) ->
                    add(buildJsonObject {
                        put("id", id)
                        put("state", state.name)
                    })
                }
            }
        }
        is SkipReason.RunningConflict -> buildJsonObject {
            put("type", "RunningConflict")
            put("taskId", reason.taskId)
            put("scope", serialiseScope(reason.scope))
        }
        is SkipReason.SelectedConflict -> buildJsonObject {
            put("type", "SelectedConflict")
            put("taskId", reason.taskId)
            put("scope", serialiseScope(reason.scope))
        }
        is SkipReason.CapacityExceeded -> buildJsonObject {
            put("type", "CapacityExceeded")
        }
        is SkipReason.AffinityPreempted -> buildJsonObject {
            put("type", "AffinityPreempted")
            put("groupId", reason.groupId)
            put("competingTaskId", reason.competingTaskId)
        }
    }

    private fun serialiseScope(scope: WriteScope): String = when (scope) {
        is WriteScope.FileScope -> "file:${scope.path}"
        is WriteScope.DirScope -> "dir:${scope.path}"
        is WriteScope.ResourceScope -> "resource:${scope.id}"
    }
}
