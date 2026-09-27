package io.agentdevkit.planner

import java.io.File

sealed interface AgentTurn {
    data class Completed(val text: String) : AgentTurn
    data class Failed(val message: String) : AgentTurn
}

/** Planning services own prompts and interpretation; adapters own agent transport. */
fun interface PlanningAgent {
    fun execute(config: ReviewerConfiguration, root: File, prompt: String): AgentTurn
}

object UnavailablePlanningAgent : PlanningAgent {
    override fun execute(config: ReviewerConfiguration, root: File, prompt: String) =
        AgentTurn.Failed("No planning agent is configured in this host")
}
