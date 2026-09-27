package io.agentdevkit.planner

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.*

class AcpFailureTest {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    private fun config(timeout: Long = 3000) = ReviewerConfiguration(listOf(java), ReadOnlyLaunch.BUBBLEWRAP, timeout, 4096)
    private fun run(mode: String, root: File, timeout: Long = 3000): AcpReviewResult = AcpIssueReviewer.reviewUsing(config(timeout), root, "Review") { _, directory ->
        ProcessBuilder(java, "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, mode).directory(directory).start()
    }

    @Test fun `missing ACP executable produces unavailable result`(@TempDir root: File) {
        val result = AcpIssueReviewer.review(ReviewerConfiguration(listOf(root.resolve("no-acp").path), ReadOnlyLaunch.BUBBLEWRAP), root, "review")
        assertEquals(AcpFailure.MISSING_EXECUTABLE, result.failure)
        assertNull(result.review)
    }

    @Test fun `missing sandbox produces start failure`(@TempDir root: File) {
        val result = AcpIssueReviewer.reviewUsing(config(), root, "review") { _, _ -> throw IOException("bwrap absent") }
        assertEquals(AcpFailure.START_FAILED, result.failure)
    }

    @ParameterizedTest
    @CsvSource("malformed,PROTOCOL_ERROR", "exit,PROTOCOL_ERROR", "wrong-version,PROTOCOL_ERROR", "auth,AGENT_ERROR",
        "flood,OUTPUT_LIMIT", "hang,TIMEOUT", "no-read,TIMEOUT", "invalid-review,INVALID_REVIEW", "refusal,AGENT_ERROR", "wrong-session,PROTOCOL_ERROR")
    fun `bad agents cannot produce accepted review`(mode: String, failure: AcpFailure, @TempDir root: File) {
        val result = if (mode == "no-read") AcpIssueReviewer.reviewUsing(config(1000), root, "x".repeat(60_000)) { _, directory ->
            ProcessBuilder(java, "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, mode).directory(directory).start()
        } else run(mode, root, 1200)
        assertEquals(failure, result.failure, result.error)
        assertNull(result.review)
    }

    @Test fun `streamed answer and denied permission complete successfully`(@TempDir root: File) {
        val result = run("good", root)
        assertNull(result.failure, result.error)
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, result.review?.verdict)
    }

    @Test fun `interrupt cleans up process and preserves caller cancellation`(@TempDir root: File) {
        val started = CountDownLatch(1)
        val process = AtomicReference<Process>()
        val result = AtomicReference<AcpReviewResult>()
        val interrupted = AtomicReference(false)
        val worker = Thread {
            result.set(AcpIssueReviewer.reviewUsing(config(30_000), root, "Review") { _, directory ->
                ProcessBuilder(java, "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, "hang").directory(directory).start().also {
                    process.set(it); started.countDown()
                }
            })
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            worker.interrupt()
            worker.join(5000)
            assertFalse(worker.isAlive)
            assertEquals(AcpFailure.CANCELLED, result.get()?.failure)
            assertTrue(interrupted.get())
            assertFalse(process.get().isAlive)
        } finally { process.get()?.destroyForcibly(); worker.interrupt() }
    }
}
