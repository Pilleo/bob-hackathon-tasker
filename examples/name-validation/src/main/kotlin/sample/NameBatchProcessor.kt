package sample

/** A second call site for NameValidator; the original blank-name bug propagates here. */
class NameBatchProcessor(
    private val normalizer: NameNormalizer,
    private val validator: NameValidator,
) {
    fun process(names: List<String>): NameBatch {
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (name in names) {
            val normalized = normalizer.normalize(name)
            if (validator.isValid(normalized)) accepted += normalized else rejected += name
        }
        return NameBatch(accepted.toList(), rejected.toList())
    }
}
