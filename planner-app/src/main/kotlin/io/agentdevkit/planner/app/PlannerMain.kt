package io.agentdevkit.planner.app

import io.agentdevkit.planner.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class PlannerCommandResult(val exitCode: Int, val text: String)

object PlannerApplication {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = false }

    fun run(root: File, args: List<String>, agent: PlanningAgent = AcpPlanningAgent,
        tools: PlanningTools = RepositoryPlanningTools()): PlannerCommandResult {
        return try {
        when (args.take(2)) {
            listOf("issue", "new") -> {
                val optional = args.drop(4)
                if (args.size < 6 || args[2] != "--title" || args[3].isBlank() || optional.size % 2 != 0 ||
                    optional.chunked(2).any { it[0] != "--target" || it[1].isBlank() }) {
                    invalid("Usage: issue new --title <text> --target <relative-file> [--target <relative-file>]...")
                }
                else {
                    val result = IssueTemplateGenerator.scaffoldStructured(args[3], root,
                        optional.chunked(2).map { it[1] })
                    ok("Created ${result.file.relativeTo(root)}. Describe the problem in Context, then run issue elaborate <issue-file>.")
                }
            }
            listOf("planner", "setup") -> setup(root, args.drop(2))
            listOf("issue", "elaborate") -> {
                val file = argumentFile(root, args, 3) ?: return invalid("Usage: issue elaborate <issue-file>")
                val config = configuration(root) ?: return invalid("Configure an ACP agent: planner setup --name <id> --agent <absolute-executable>")
                val profile = config.reviewers[config.defaultReviewer] ?: return invalid("Default agent ${config.defaultReviewer} is not configured")
                val result = ElaborationService.elaborate(file, root, profile, tools, agent)
                PlannerCommandResult(if (result.success) 0 else 4, result.message)
            }
            listOf("issue", "show") -> {
                val file = argumentFile(root, args, 3) ?: return invalid("Usage: issue show <issue-file>")
                val view = IssueReviewView.inspect(root, file)
                PlannerCommandResult(if (view is IssueReviewView.Unreadable) 4 else 0, IssueReviewView.render(view))
            }
            else -> when (args.firstOrNull()) {
                "schedule" -> ScheduleCommand.run(args.drop(1), root)
                "planning-context" -> {
                    val file = argumentFile(root, args, 2) ?: return invalid("Usage: planning-context <issue-file>")
                    val read = PlanningDocumentReader.read(file, root)
                    if (read !is DocumentRead.Valid) invalid("Issue is not a valid schema-v2 document") else {
                        val evidence = PlanningEvidenceCollector.collect(read.document, root, tools)
                        ok("Evidence: ${evidence.status}; steps: ${evidence.steps.size}; gaps: ${evidence.issues.joinToString { it.code }}")
                    }
                }
                "review-issue" -> {
                    val file = argumentFile(root, args, 2) ?: return invalid("Usage: review-issue <issue-file>")
                    val config = configuration(root) ?: return invalid("Configure an ACP agent with planner setup")
                    val result = IssueReviewService.review(file, root, config.defaultReviewer, config, tools, agent)
                    PlannerCommandResult(if (result.errors.isEmpty()) 0 else 5,
                        if (result.errors.isEmpty()) IssueReviewView.render(IssueReviewView.Current(result.artifact!!)) else result.errors.joinToString("\n"))
                }
                else -> invalid("Commands: issue new --title <text> --target <relative-file>... | issue elaborate <file> | issue show <file> | planner setup --name <id> --agent <absolute-file> [--arg <value>]... | planning-context <file> | review-issue <file> | schedule --capacity N (--issues <file>... | --fixture <path>)")
            }
        }
        } catch (error: Exception) { invalid("Planner operation failed: ${error.message}") }
    }

    private fun setup(root: File, args: List<String>): PlannerCommandResult {
        val usage = "Usage: planner setup --name <id> --agent <absolute-executable> [--arg <value>]... [--model <id>]"
        val parsed = PlannerSetupOptions.parse(args, allowDiscovery = false)
        if (parsed is SetupOptionsResult.Invalid) return invalid("$usage: ${parsed.message}")
        val options = (parsed as SetupOptionsResult.Valid).options
        val name = options.name
        val executable = File(options.agent ?: return invalid(usage))
        if (!executable.isAbsolute || !executable.isFile || !executable.canExecute()) return invalid("ACP agent must be an absolute executable file")
        val existing = configuration(root) ?: PlannerReviewConfiguration()
        val prior = existing.reviewers[name]
        val profile = (prior ?: ReviewerConfiguration(listOf(executable.canonicalPath))).copy(
            command = listOf(executable.canonicalPath) + options.arguments,
            readOnly = ReadOnlyLaunch.BUBBLEWRAP,
            model = options.model ?: prior?.model,
        )
        val updated = existing.copy(defaultReviewer = name, reviewers = existing.reviewers + (name to profile))
        val file = root.resolve(".planner.json")
        if (Files.isSymbolicLink(file.toPath())) return invalid("Planner configuration cannot be a symlink")
        val temporary = Files.createTempFile(root.toPath(), ".planner-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(updated) + "\n")
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
        return ok("Configured $name at ${executable.canonicalPath}. Live ACP use requires bubblewrap and agent authentication.")
    }

    private fun configuration(root: File): PlannerReviewConfiguration? = root.resolve(".planner.json").takeIf { it.isFile }
        ?.let { json.decodeFromString<PlannerReviewConfiguration>(it.readText()) }

    private fun argumentFile(root: File, args: List<String>, count: Int): File? {
        if (args.size != count || File(args.last()).isAbsolute) return null
        val file = File(root, args.last())
        return file.takeIf { !Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() <= 262_144 &&
            it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }
    }

    private fun ok(text: String) = PlannerCommandResult(0, text)
    private fun invalid(text: String) = PlannerCommandResult(4, text)
}

fun main(args: Array<String>) {
    val result = PlannerApplication.run(File(".").canonicalFile, args.toList())
    if (result.exitCode == 0) println(result.text) else System.err.println(result.text)
    if (result.exitCode != 0) kotlin.system.exitProcess(result.exitCode)
}
