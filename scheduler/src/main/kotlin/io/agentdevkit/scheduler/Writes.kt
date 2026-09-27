package io.agentdevkit.scheduler

sealed class Writes {
    data class Known(val scopes: List<WriteScope>) : Writes()
    object Unknown : Writes()
}
