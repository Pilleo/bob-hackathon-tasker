package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

@EnabledIfEnvironmentVariable(named = "ADK_SANDBOX_TESTS", matches = "1")
class AcpSandboxTest {
    @Test fun `real sandbox denies checkout writes with workspace under tmp`(@TempDir root: File) {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val config = ReviewerConfiguration(listOf(java, "-cp", System.getProperty("fixture.classpath"), AcpFixture::class.java.name, "sandbox"),
            ReadOnlyLaunch.BUBBLEWRAP, 10_000)
        val result = AcpIssueReviewer.review(config, root, "review")
        assertNull(result.error, result.error)
        assertNotNull(result.review)
        assertFalse(root.resolve("forbidden.txt").exists())
    }
}
