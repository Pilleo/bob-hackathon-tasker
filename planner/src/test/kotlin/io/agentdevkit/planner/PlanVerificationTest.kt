package io.agentdevkit.planner

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PlanVerificationTest {
    @Test fun `a draft without current review cannot become a scheduling candidate`(@TempDir root: File) {
        val issue = IssueTemplateGenerator.scaffoldStructured("Reject blanks", root, listOf("src/Validator.kt")).file
        val result = PlanVerification.verify(issue, root, UnavailablePlanningTools)
        assertIs<PlanVerification.Rejected>(result)
    }
}
