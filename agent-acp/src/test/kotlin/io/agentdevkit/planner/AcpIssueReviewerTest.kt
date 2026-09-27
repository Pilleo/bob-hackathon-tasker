package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AcpIssueReviewerTest {
    private val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    private fun run(root: File, mode: String, timeout: Long): AcpReviewResult = AcpIssueReviewer.reviewUsing(
        ReviewerConfiguration(listOf(java), ReadOnlyLaunch.BUBBLEWRAP, timeout), root, "Review this draft",
    ) { _, directory -> ProcessBuilder(java, "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, mode).directory(directory).start() }

    @Test fun `scripted ACP server review collects message chunks and rejects writes`(@TempDir root: File) {
        val result = run(root, "good", 5000)
        assertEquals(ReviewVerdict.CHANGES_REQUESTED, result.review?.verdict, result.error)
        assertTrue(result.error == null, result.error.orEmpty())
    }

    @Test fun `unqualified reviewer never launches`() {
        val result = AcpIssueReviewer.review(ReviewerConfiguration(listOf("/does/not/exist"), ReadOnlyLaunch.UNQUALIFIED), File("."), "review")
        assertTrue(result.error.orEmpty().contains("read-only"))
    }

    @Test fun `ACP timeout returns transport failure rather than verdict`(@TempDir root: File) {
        val result = run(root, "hang", 150)
        assertEquals(null, result.review)
        assertTrue(result.error.orEmpty().contains("timed out"), result.error)
    }

    @Test fun `agent requiring unsupported client tools cannot produce a clean review`(@TempDir root: File) {
        val result = run(root, "needs-client-filesystem", 5_000)
        assertEquals(null, result.review)
        assertEquals(AcpFailure.AGENT_ERROR, result.failure)
        assertTrue(result.error.orEmpty().contains("Client filesystem capability unavailable"), result.error.orEmpty())
    }

    @Test fun `generic ACP profiles do not inherit Antigravity home overlays`(@TempDir root: File) {
        val home = root.resolve("home")
        val antigravityCache = home.resolve(".gemini/config").apply { mkdirs() }
        val generic = AcpIssueReviewer.sandboxCommand(ReviewerConfiguration(listOf(root.resolve("generic-acp").path)), root, home)
        assertFalse(generic.windowed(2).contains(listOf("--tmpfs", antigravityCache.path)))
        val antigravity = AcpIssueReviewer.sandboxCommand(ReviewerConfiguration(listOf(root.resolve("agy_acp_server.par").path)), root, home)
        assertTrue(antigravity.windowed(2).contains(listOf("--tmpfs", antigravityCache.path)))
    }

    @Test fun `optional model selection fails with an actionable profile error when unsupported`(@TempDir root: File) {
        val config = ReviewerConfiguration(listOf(java), ReadOnlyLaunch.BUBBLEWRAP, 5_000, 4_096, "requested-model")
        val result = AcpIssueReviewer.reviewUsing(config, root, "Review") { _, directory ->
            ProcessBuilder(java, "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, "no-model-config")
                .directory(directory).start()
        }
        assertEquals(null, result.review)
        assertEquals(AcpFailure.INVALID_CONFIGURATION, result.failure)
        assertTrue(result.error.orEmpty().contains("model selection"), result.error.orEmpty())
    }
}
