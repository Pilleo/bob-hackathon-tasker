package io.agentdevkit.planner

data class SetupOptions(val name: String, val agent: String?, val arguments: List<String>, val model: String?)

sealed interface SetupOptionsResult {
    data class Valid(val options: SetupOptions) : SetupOptionsResult
    data class Invalid(val message: String) : SetupOptionsResult
}

/** Shared option decoding for both ADK and the standalone planner host. */
object PlannerSetupOptions {
    fun parse(tokens: List<String>, allowDiscovery: Boolean): SetupOptionsResult {
        var name: String? = null
        var agent: String? = null
        var model: String? = null
        val arguments = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val option = tokens[index]
            val value = tokens.getOrNull(index + 1)
            if (value.isNullOrBlank() || value.any(Char::isISOControl)) {
                return SetupOptionsResult.Invalid("$option requires a nonblank value without control characters")
            }
            when (option) {
                "--name" -> {
                    if (name != null || value.startsWith("--")) return SetupOptionsResult.Invalid("--name must occur once")
                    name = value
                }
                "--agent" -> {
                    if (agent != null || value.startsWith("--")) return SetupOptionsResult.Invalid("--agent must occur once")
                    agent = value
                }
                "--model" -> {
                    if (model != null || value.startsWith("--")) return SetupOptionsResult.Invalid("--model must occur once")
                    model = value
                }
                "--arg" -> {
                    if (value == "--arg") return SetupOptionsResult.Invalid("--arg must have a value")
                    arguments += value
                }
                else -> return SetupOptionsResult.Invalid("Unknown planner setup option $option")
            }
            index += 2
        }
        val selected = name ?: if (allowDiscovery) "antigravity" else
            return SetupOptionsResult.Invalid("Use --name <id> for a standalone agent")
        if (!selected.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,40}"))) return SetupOptionsResult.Invalid("Reviewer name must be a short identifier")
        if (agent == null && (!allowDiscovery || selected != "antigravity" || arguments.isNotEmpty())) {
            return SetupOptionsResult.Invalid("Provide --agent <absolute-executable> for $selected")
        }
        if (arguments.size > 32) return SetupOptionsResult.Invalid("At most 32 agent startup arguments are supported")
        return SetupOptionsResult.Valid(SetupOptions(selected, agent, arguments.toList(), model))
    }
}
