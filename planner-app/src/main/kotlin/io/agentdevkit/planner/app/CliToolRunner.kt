package io.agentdevkit.planner.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

data class CommandResult(val exitCode: Int?, val stdout: String, val failure: String? = null)

/** Bound process time and retained output while the tool runs; own its streams and descendants. */
object CliToolRunner {
    fun run(arguments: List<String>, root: File, timeoutMs: Long = 15_000): CommandResult {
        if (timeoutMs !in 1..60_000) return CommandResult(null, "", "Invalid tool timeout")
        val process = try { ProcessBuilder(arguments).directory(root).start() }
            catch (error: IOException) { return CommandResult(null, "", "Tool unavailable: ${error.message}") }
        val failure = AtomicReference<String>()
        val children = linkedSetOf<ProcessHandle>()
        fun capture(stream: java.io.InputStream, maximum: Int, label: String): Pair<ByteArrayOutputStream, Thread> {
            val buffer = ByteArrayOutputStream()
            val reader = Thread.ofVirtual().start {
                try {
                    stream.use { input ->
                        val chunk = ByteArray(4_096)
                        while (true) {
                            val count = input.read(chunk)
                            if (count < 0) break
                            if (buffer.size() + count > maximum) {
                                failure.compareAndSet(null, "$label output exceeded limit")
                                break
                            }
                            buffer.write(chunk, 0, count)
                        }
                    }
                } catch (error: IOException) {
                    if (process.isAlive && failure.get() == null) failure.compareAndSet(null, "$label stream failed: ${error.message}")
                }
            }
            return buffer to reader
        }
        val (stdout, stdoutReader) = capture(process.inputStream, 256_000, "Tool stdout")
        val (stderr, stderrReader) = capture(process.errorStream, 16_000, "Tool stderr")
        var interrupted = false
        var completed = false
        try {
            process.outputStream.close()
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                process.descendants().use { it.forEach(children::add) }
                if (failure.get() != null) break
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) { failure.compareAndSet(null, "Tool timed out"); break }
                if (process.waitFor(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) {
                    completed = true
                    break
                }
            }
        } catch (error: InterruptedException) {
            interrupted = true
            throw error
        } finally {
            if (failure.get() != null || !completed || children.any { it.isAlive }) {
                process.descendants().use { it.forEach(children::add) }
                children.forEach { if (it.isAlive) it.destroyForcibly() }
                val cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                children.forEach { child ->
                    val remaining = cleanupDeadline - System.nanoTime()
                    if (child.isAlive && remaining > 0) try {
                        child.onExit().get(remaining, TimeUnit.NANOSECONDS)
                    } catch (_: TimeoutException) { /* liveness check below reports incomplete cleanup */ }
                    catch (_: java.util.concurrent.ExecutionException) { /* liveness remains authoritative */ }
                    catch (_: InterruptedException) { interrupted = true }
                }
                if (process.isAlive) process.destroyForcibly()
            }
            try {
                process.waitFor(2, TimeUnit.SECONDS)
                stdoutReader.join(1_000)
                stderrReader.join(1_000)
            } catch (_: InterruptedException) { interrupted = true }
            if (stdoutReader.isAlive) stdoutReader.interrupt()
            if (stderrReader.isAlive) stderrReader.interrupt()
            if (children.any { it.isAlive } || process.isAlive || stdoutReader.isAlive || stderrReader.isAlive) {
                failure.set("Tool process cleanup incomplete")
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        failure.get()?.let { return CommandResult(null, "", it) }
        val diagnostic = if (process.exitValue() == 0) null else stderr.toString(Charsets.UTF_8).take(400).takeIf { it.isNotBlank() }
        return CommandResult(process.exitValue(), stdout.toString(Charsets.UTF_8), diagnostic)
    }
}
