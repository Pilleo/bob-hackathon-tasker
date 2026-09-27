package io.agentdevkit.planner.app

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

object ToolFixture {
    @JvmStatic fun main(args: Array<String>) {
        when (args.single()) {
            "flood" -> {
                System.out.write(ByteArray(512_000) { 'x'.code.toByte() })
                System.out.flush()
                Thread.sleep(20_000)
            }
            "hang" -> Thread.sleep(20_000)
            "parent", "parent-exit" -> {
                val java = File(System.getProperty("java.home"), "bin/java").absolutePath
                val child = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ToolFixture::class.java.name, "hang").start()
                File("child.pid").writeText(child.pid().toString())
                Thread.sleep(if (args.single() == "parent-exit") 300 else 20_000)
            }
        }
    }
}

class CliToolRunnerTest {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath

    @Test fun `flooding subprocess is stopped before the tool timeout`(@TempDir root: File) {
        val started = System.nanoTime()
        val result = CliToolRunner.run(listOf(java, "-cp", System.getProperty("fixture.classpath"), ToolFixture::class.java.name, "flood"), root)
        val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        assertNull(result.exitCode)
        assertContains(result.failure.orEmpty(), "limit", ignoreCase = true)
        assertTrue(elapsed < 5, "Output limit took ${elapsed}s to enforce")
    }

    @Test fun `timed out tool terminates its child process`(@TempDir root: File) {
        val result = CliToolRunner.run(listOf(java, "-cp", System.getProperty("fixture.classpath"), ToolFixture::class.java.name, "parent"), root, 1_000)
        assertNull(result.exitCode)
        assertContains(result.failure.orEmpty(), "timed out")
        val pid = root.resolve("child.pid").readText().trim().toLong()
        val child = ProcessHandle.of(pid).orElse(null)
        try { assertFalse(child?.isAlive == true, "Tool descendant $pid is still running") }
        finally { if (child?.isAlive == true) child.destroyForcibly() }
    }

    @Test fun `successful parent exit also terminates its child process`(@TempDir root: File) {
        val result = CliToolRunner.run(listOf(java, "-cp", System.getProperty("fixture.classpath"), ToolFixture::class.java.name, "parent-exit"), root, 3_000)
        val pid = root.resolve("child.pid").readText().trim().toLong()
        val child = ProcessHandle.of(pid).orElse(null)
        try {
            assertFalse(child?.isAlive == true, "Tool descendant $pid is still running: $result")
            assertEquals(0, result.exitCode, result.failure)
        } finally { if (child?.isAlive == true) child.destroyForcibly() }
    }
}
