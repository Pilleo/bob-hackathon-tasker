package io.agentdevkit.planner

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*

/** One bounded, interactive stdio connection. Threads and processes are owned by this scope. */
internal class AcpConnection(private val process: Process, timeoutMs: Long, private val limit: Int) {
    var sessionId: String? = null
    val messages = StringBuilder()
    var interruptedDuringCleanup = false
        private set
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    private val queue = ArrayBlockingQueue<String>(256)
    private val fault = AtomicReference<AcpProblem>()
    private val descendants = linkedSetOf<ProcessHandle>()
    private val writer = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory())
    private val stderr = ByteArrayOutputStream()
    private val stdoutThread = Thread.ofVirtual().start {
        try {
            process.inputStream.buffered().use { input ->
                val line = ByteArrayOutputStream()
                var count = 0
                while (true) {
                    val byte = input.read()
                    if (byte < 0) { if (line.size() != 0) enqueue(line.toString(Charsets.UTF_8)); break }
                    if (++count > limit) throw AcpProblem(AcpFailure.OUTPUT_LIMIT, "ACP output exceeds configured limit")
                    if (byte == 10) { enqueue(line.toString(Charsets.UTF_8)); line.reset() } else line.write(byte)
                }
            }
            // EOF follows all complete messages; do not preempt an already queued response.
            enqueue("\u0000EOF")
        } catch (error: AcpProblem) { fault.compareAndSet(null, error) }
        catch (error: IOException) { fault.compareAndSet(null, AcpProblem(AcpFailure.PROTOCOL_ERROR, "ACP output closed: ${error.message}")) }
    }
    private val stderrThread = Thread.ofVirtual().start {
        try {
            process.errorStream.use { input ->
                val bytes = ByteArray(1024)
                while (true) {
                    val read = input.read(bytes)
                    if (read < 0) break
                    synchronized(stderr) { stderr.write(bytes, 0, minOf(read, maxOf(0, 4096 - stderr.size()))) }
                }
            }
        } catch (_: IOException) { /* stream closure during owned process cleanup */ }
    }

    private fun enqueue(line: String) {
        if (!queue.offer(line)) throw AcpProblem(AcpFailure.OUTPUT_LIMIT, "ACP pending-message budget exceeded")
    }

    private fun remaining(): Long = (deadline - System.nanoTime()).also {
        if (it <= 0) throw AcpProblem(AcpFailure.TIMEOUT, "ACP timed out")
    }

    private fun send(value: JsonObject, budget: Long = remaining()) {
        val future = writer.submit {
            process.outputStream.write((value.toString() + "\n").toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
        }
        try { future.get(budget, TimeUnit.NANOSECONDS) }
        catch (_: TimeoutException) { future.cancel(true); throw AcpProblem(AcpFailure.TIMEOUT, "ACP timed out writing request") }
        catch (error: ExecutionException) { throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "ACP input closed: ${error.cause?.message}") }
    }

    fun request(id: Int, method: String, params: JsonObject): JsonObject {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        while (true) {
            process.descendants().use { it.forEach(descendants::add) }
            fault.get()?.let { throw it }
            val line = queue.poll(minOf(remaining(), TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS) ?: continue
            if (line == "\u0000EOF") throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "ACP exited before $method completed")
            val message = Json.parseToJsonElement(line).jsonObject
            if (message["jsonrpc"]?.jsonPrimitive?.contentOrNull != "2.0") throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "Invalid JSON-RPC version")
            val incomingMethod = message["method"]?.jsonPrimitive?.contentOrNull
            when {
                incomingMethod == "session/request_permission" -> {
                    val requestParams = message.getValue("params").jsonObject
                    checkSession(requestParams)
                    val reject = requestParams["options"]?.jsonArray?.firstOrNull {
                        it.jsonObject["kind"]?.jsonPrimitive?.contentOrNull in setOf("reject_once", "reject_always")
                    }?.jsonObject
                    val outcome = buildJsonObject {
                        if (reject == null) put("outcome", "cancelled") else {
                            put("outcome", "selected"); put("optionId", reject.getValue("optionId"))
                        }
                    }
                    send(buildJsonObject { put("jsonrpc", "2.0"); put("id", message.getValue("id")); put("result", buildJsonObject { put("outcome", outcome) }) })
                }
                incomingMethod == "session/update" -> {
                    val updateParams = message.getValue("params").jsonObject
                    checkSession(updateParams)
                    val update = updateParams.getValue("update").jsonObject
                    when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
                        "agent_message_chunk" -> update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let(messages::append)
                        "current_mode_update" -> if (update["currentModeId"]?.jsonPrimitive?.contentOrNull in setOf("auto_edit", "yolo")) throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "Reviewer switched to unsafe mode")
                    }
                }
                incomingMethod != null && message["id"] != null -> send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", message.getValue("id")); put("error", buildJsonObject {
                        put("code", -32601)
                        put("message", if (incomingMethod.startsWith("fs/") || incomingMethod.startsWith("terminal/"))
                            "Client filesystem capability unavailable: $incomingMethod" else "Client capability unavailable: $incomingMethod")
                    })
                })
                incomingMethod == null && message["id"]?.jsonPrimitive?.contentOrNull == id.toString() -> {
                    message["error"]?.let { throw AcpProblem(AcpFailure.AGENT_ERROR, "ACP $method failed: $it") }
                    return message["result"]?.jsonObject ?: throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "ACP result missing")
                }
            }
        }
    }

    private fun checkSession(params: JsonObject) {
        if (sessionId != null && params["sessionId"]?.jsonPrimitive?.contentOrNull != sessionId) throw AcpProblem(AcpFailure.PROTOCOL_ERROR, "Unexpected ACP session")
    }

    fun close(cancel: Boolean): Boolean {
        if (Thread.interrupted()) interruptedDuringCleanup = true
        val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        fun budget(): Long = maxOf(0, cleanupDeadline - System.nanoTime())
        // One failing close/termination must not skip cleanup of the other resources.
        fun attempt(action: () -> Unit) {
            try { action() }
            catch (_: InterruptedException) { interruptedDuringCleanup = true }
            catch (_: Exception) { /* final liveness checks decide whether cleanup succeeded */ }
        }
        if (cancel && sessionId != null && process.isAlive) attempt {
            send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "session/cancel"); put("params", buildJsonObject { put("sessionId", sessionId) }) }, minOf(budget(), TimeUnit.MILLISECONDS.toNanos(100)))
        }
        var descendantsInspected = false
        attempt { process.descendants().use { it.forEach(descendants::add) }; descendantsInspected = true }
        descendants.forEach { child -> attempt { if (child.isAlive) child.destroyForcibly() } }
        // Give the still-live parent a chance to reap its children before terminating it.
        descendants.forEach { child -> attempt { if (child.isAlive) child.onExit().get(budget(), TimeUnit.NANOSECONDS) } }
        attempt { process.destroyForcibly() }
        writer.shutdownNow()
        attempt { process.waitFor(budget(), TimeUnit.NANOSECONDS) }
        // A stream close can itself block on an I/O monitor held by a writer.
        // Own those workers too, and never wait past the shared cleanup deadline.
        val closers = listOf(process.inputStream, process.errorStream, process.outputStream).map { stream ->
            Thread.ofVirtual().start {
                try { stream.close() } catch (_: Exception) { /* liveness below remains authoritative */ }
            }
        }
        closers.forEach { closer -> attempt { if (budget() > 0) TimeUnit.NANOSECONDS.timedJoin(closer, budget()) } }
        attempt { if (budget() > 0) TimeUnit.NANOSECONDS.timedJoin(stdoutThread, budget()) }
        attempt { if (budget() > 0) TimeUnit.NANOSECONDS.timedJoin(stderrThread, budget()) }
        attempt { writer.awaitTermination(budget(), TimeUnit.NANOSECONDS) }
        closers.filter { it.isAlive }.forEach(Thread::interrupt)
        if (Thread.interrupted()) interruptedDuringCleanup = true
        return descendantsInspected && descendants.none { it.isAlive } && !process.isAlive &&
            closers.none { it.isAlive } && !stdoutThread.isAlive && !stderrThread.isAlive && writer.isTerminated
    }
}
