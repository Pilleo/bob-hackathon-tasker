package sample

class NameSummaryFormatter {
    fun format(batch: NameBatch): String = "accepted=${batch.accepted.size}, rejected=${batch.rejected.size}"
}
