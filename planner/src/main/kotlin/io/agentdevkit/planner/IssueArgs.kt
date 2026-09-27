package io.agentdevkit.planner

sealed interface IssueArgs {
    data class Valid(val request: IssueRequest) : IssueArgs
    data class Invalid(val message: String) : IssueArgs

    companion object {
        private val valueFlags = setOf(
            "--title", "--severity", "--priority", "--component",
            "--file", "--symbol", "--context", "--step",
        )

        fun parse(arguments: List<String>): IssueArgs {
            var title: String? = null
            var severity = "MEDIUM"
            var priority = "high"
            var component = "core"
            val targetFiles = mutableListOf<String>()
            val targetSymbols = mutableListOf<String>()
            var context = ""
            val needed = mutableListOf<String>()
            var index = 0
            while (index < arguments.size) {
                val flag = arguments[index]
                if (flag !in valueFlags) {
                    return Invalid("Unknown option: $flag")
                }
                if (index + 1 >= arguments.size || arguments[index + 1].startsWith("--")) {
                    return Invalid("$flag requires a value")
                }
                val value = arguments[index + 1]
                when (flag) {
                    "--title" -> title = value
                    "--severity" -> severity = value
                    "--priority" -> priority = value
                    "--component" -> component = value
                    "--file" -> targetFiles += value
                    "--symbol" -> targetSymbols += value
                    "--context" -> context = value
                    "--step" -> needed += value
                }
                index += 2
            }
            if (title.isNullOrBlank()) {
                return Invalid("new-issue requires --title <text>")
            }
            return Valid(
                IssueRequest(
                    title = title,
                    severity = severity,
                    priority = priority,
                    component = component,
                    targetFiles = targetFiles,
                    targetSymbols = targetSymbols,
                    context = context,
                    needed = needed,
                ),
            )
        }
    }
}
