package io.agentdevkit.planner

import java.io.File

/** Transport-only adapter; planner services interpret and validate the returned text. */
object AcpPlanningAgent : PlanningAgent {
    override fun execute(config: ReviewerConfiguration, root: File, prompt: String): AgentTurn {
        val response = AcpIssueReviewer.ask(config, root, prompt)
        return response.text?.let(AgentTurn::Completed)
            ?: AgentTurn.Failed(response.error ?: "ACP agent returned no response")
    }
}
