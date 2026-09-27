package sample

/** Canonicalizes spacing before downstream name checks. */
class NameNormalizer {
    fun normalize(rawName: String): String = rawName.trim().replace(Regex("\\s+"), " ")
}
