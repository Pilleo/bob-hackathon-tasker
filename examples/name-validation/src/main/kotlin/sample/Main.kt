package sample

/** Intentionally permissive sample to investigate and plan a validation change. */
class NameValidator {
    fun isValid(name: String): Boolean = name.length >= 0
}

fun main() {
    val validator = NameValidator()
    println("Blank name accepted: ${validator.isValid("   ")}")
    println("Nonblank name accepted: ${validator.isValid("Alice")}")

    val batch = NameBatchProcessor(NameNormalizer(), validator).process(listOf("  Ada  Lovelace ", "   ", "Bob"))
    println("Normalized accepted names: ${batch.accepted}")
    println("Rejected names: ${batch.rejected}")
    println("Batch summary: ${NameSummaryFormatter().format(batch)}")
}
