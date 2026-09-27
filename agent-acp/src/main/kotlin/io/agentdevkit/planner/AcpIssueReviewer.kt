package io.agentdevkit.planner

import java.io.File
import kotlinx.serialization.json.*

enum class AcpFailure { INVALID_CONFIGURATION, MISSING_EXECUTABLE, START_FAILED, PROTOCOL_ERROR, AGENT_ERROR, TIMEOUT, CANCELLED, OUTPUT_LIMIT, INVALID_REVIEW, CLEANUP_FAILED }

data class AcpTextResult(val text: String? = null, val error: String? = null, val failure: AcpFailure? = null)

data class AcpReviewResult(
    val review: IssueReview? = null,
    val error: String? = null,
    val agentVersion: String? = null,
    val failure: AcpFailure? = null,
)

internal class AcpProblem(val kind: AcpFailure, message: String) : Exception(message)

object AcpIssueReviewer {
    fun review(config: ReviewerConfiguration, root: File, prompt: String): AcpReviewResult =
        reviewUsing(config, root, prompt) { command, directory -> ProcessBuilder(command).directory(directory).start() }

    fun ask(config: ReviewerConfiguration, root: File, prompt: String): AcpTextResult =
        askUsing(config, root, prompt) { command, directory -> ProcessBuilder(command).directory(directory).start() }

    internal fun reviewUsing(config: ReviewerConfiguration, root: File, prompt: String,
        launch: (List<String>, File) -> Process): AcpReviewResult {
        val outcome = session(config, root, prompt, launch)
        val failure = outcome.failure ?: return run {
            val parsed = IssueReview.parse(outcome.text.orEmpty())
            if (parsed.review == null) AcpReviewResult(error = parsed.errors.joinToString(), failure = AcpFailure.INVALID_REVIEW, agentVersion = outcome.version)
            else AcpReviewResult(review = parsed.review, agentVersion = outcome.version)
        }
        return AcpReviewResult(error = failure.error, failure = failure.failure)
    }

    internal fun askUsing(config: ReviewerConfiguration, root: File, prompt: String, launch: (List<String>, File) -> Process): AcpTextResult {
        val outcome = session(config, root, prompt, launch)
        return outcome.failure?.let { AcpTextResult(error = it.error, failure = it.failure) } ?: AcpTextResult(text = outcome.text)
    }

    private data class Session(val text: String? = null, val version: String? = null, val failure: AcpReviewResult? = null)

    private fun session(config: ReviewerConfiguration, root: File, prompt: String, launch: (List<String>, File) -> Process): Session {
        fun failure(kind: AcpFailure, message: String) = Session(failure = AcpReviewResult(error = message, failure = kind))
        if (config.readOnly == ReadOnlyLaunch.UNQUALIFIED) return failure(AcpFailure.INVALID_CONFIGURATION, "Reviewer has no qualified read-only launch")
        if (config.command.isEmpty() || config.command.any { it.isBlank() || '\u0000' in it }) return failure(AcpFailure.INVALID_CONFIGURATION, "Reviewer command must be a non-empty argument array")
        val executable = File(config.command.first())
        if (!executable.isAbsolute || !executable.isFile || !executable.canExecute()) return failure(AcpFailure.MISSING_EXECUTABLE, "Reviewer executable is missing or not executable: $executable")
        if (!root.isDirectory || config.timeoutMs !in 100..600_000 || config.maxOutputBytes !in 1024..1_048_576 || prompt.toByteArray().size > 128_000) {
            return failure(AcpFailure.INVALID_CONFIGURATION, "Reviewer directory, prompt size, or limits are invalid")
        }
        val process = try { launch(sandboxCommand(config, root), root) } catch (error: Exception) {
            return failure(AcpFailure.START_FAILED, "Reviewer failed to start: ${error.message}")
        }
        val connection = AcpConnection(process, config.timeoutMs, config.maxOutputBytes)
        var cancelled = false
        val result = try {
            val init = connection.request(1, "initialize", buildJsonObject {
                put("protocolVersion", 1); put("clientCapabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject { put("name", "agent-devkit"); put("version", "0.1.0") })
            })
            if (init["protocolVersion"]?.jsonPrimitive?.contentOrNull != "1") throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "Unsupported ACP protocol")
            val version = init["agentInfo"]?.jsonObject?.get("version")?.jsonPrimitive?.contentOrNull
            val created = connection.request(2, "session/new", buildJsonObject {
                put("cwd", root.canonicalPath); put("mcpServers", buildJsonArray {})
            })
            connection.sessionId = created["sessionId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "ACP session missing ID")
            if (created["modes"]?.jsonObject?.get("currentModeId")?.jsonPrimitive?.contentOrNull in setOf("auto_edit", "yolo")) {
                throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "Unsafe ACP session mode")
            }
            config.model?.let { model ->
                try {
                    connection.request(3, "session/set_config_option", buildJsonObject {
                        put("sessionId", connection.sessionId); put("configId", "model"); put("value", model)
                    })
                } catch (error: AcpProblem) {
                    if (error.kind == AcpFailure.AGENT_ERROR) throw AcpProblem(AcpFailure.INVALID_CONFIGURATION,
                        "ACP agent does not support model selection '$model': ${error.message}")
                    throw error
                }
            }
            connection.messages.setLength(0)
            val completed = connection.request(4, "session/prompt", buildJsonObject {
                put("sessionId", connection.sessionId)
                put("prompt", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", prompt) }) })
            })
            if (completed["stopReason"]?.jsonPrimitive?.contentOrNull != "end_turn") throw AcpProblem(AcpFailure.AGENT_ERROR, "ACP turn did not complete normally: ${completed["stopReason"]}")
            Session(text = connection.messages.toString(), version = version)
        } catch (error: InterruptedException) {
            cancelled = true
            failure(AcpFailure.CANCELLED, "ACP review cancelled")
        } catch (error: AcpProblem) {
            failure(error.kind, error.message.orEmpty())
        } catch (error: Exception) {
            failure(AcpFailure.PROTOCOL_ERROR, "ACP review failed: ${error.message}")
        }
        val clean = connection.close(result.failure != null)
        if (cancelled || connection.interruptedDuringCleanup) Thread.currentThread().interrupt()
        return if (!clean) failure(AcpFailure.CLEANUP_FAILED, "ACP process cleanup incomplete; ${result.failure?.error.orEmpty()}") else result
    }

    internal fun sandboxCommand(config: ReviewerConfiguration, root: File,
        home: File = File(System.getProperty("user.home"))): List<String> {
        return buildList {
            addAll(listOf("bwrap", "--die-with-parent", "--new-session", "--unshare-pid", "--ro-bind", "/", "/",
                "--tmpfs", "/tmp", "--dev", "/dev", "--proc", "/proc"))
            if (File(config.command.first()).name == "agy_acp_server.par") {
                for (relative in listOf(".cache", ".gemini/config", ".gemini/antigravity-acp/conversations", ".gemini/antigravity-acp/brain")) {
                    val directory = File(home, relative)
                    if (directory.isDirectory) addAll(listOf("--tmpfs", directory.path))
                }
            }
            // Workspaces under /tmp must remain visible, and always read-only.
            addAll(listOf("--ro-bind", root.absolutePath, root.absolutePath, "--chdir", root.absolutePath, "--"))
            addAll(config.command)
        }
    }
}
