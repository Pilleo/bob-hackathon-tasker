package io.agentdevkit.planner

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class StoredReview(
    val schemaVersion: Int = 1,
    val issuePath: String,
    val issueSha256: String,
    val evidence: PlanningEvidence,
    val reviewer: String,
    val review: IssueReview,
    val authorSha256: String? = null,
    val evidenceSha256: String? = null,
    val renderedBlockSha256: String? = null,
) {
    @kotlinx.serialization.Transient
    var file: File = File("")

    fun isCurrent(root: File, revision: (File) -> String? = { null }): Boolean = boundary({ false }) { File(root, issuePath).takeIf { it.isFile }?.let {
        (if (schemaVersion == 2) hash(ReviewProjection.authoredBytes(it.readText())) == authorSha256 else hash(it.readBytes()) == issueSha256) &&
            evidence.files.all { fingerprint -> fingerprint.matches(root) } &&
            evidence.repositoryRevision == revision(root)
    } ?: false }
}

object IssueReviewStore {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun save(root: File, issue: File, evidence: PlanningEvidence, reviewer: String, review: IssueReview,
        revision: (File) -> String? = { null }, expectedIssueSha256: String = hash(issue.readBytes()),
        schemaVersion: Int = 1, expectedAuthorSha256: String? = null, expectedBlockSha256: String? = null): StoredReview {
        val relative = issue.canonicalFile.relativeTo(root.canonicalFile).path
        require(relative.startsWith("docs/internals/backlog/issue-") && !relative.contains("/resolved/")) { "Issue must be active backlog file" }
        val artifact = StoredReview(schemaVersion = schemaVersion, issuePath = relative, issueSha256 = expectedIssueSha256,
            evidence = evidence, reviewer = reviewer, review = review, authorSha256 = expectedAuthorSha256,
            evidenceSha256 = hash(evidence.toString().toByteArray()), renderedBlockSha256 = expectedBlockSha256)
        require(artifact.isCurrent(root, revision)) { "Issue or evidence changed during review" }
        val expected = File(root, "docs/internals/reviews")
        require(!expected.exists() || !Files.isSymbolicLink(expected.toPath())) { "Review directory must not be a symlink" }
        val directory = File(expected, issue.nameWithoutExtension)
        require(!directory.exists() || !Files.isSymbolicLink(directory.toPath())) { "Review directory must not be a symlink" }
        directory.mkdirs()
        require(directory.canonicalFile.parentFile == expected) { "Review directory must be inside the repository" }
        val destination = File(directory, "review-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.json")
        val temporary = Files.createTempFile(directory.toPath(), ".review-", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(artifact) + "\n")
            require(artifact.isCurrent(root, revision)) { "Issue or evidence changed during review" }
            Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return artifact.apply { file = destination }
    }

    fun load(file: File): StoredReview = json.decodeFromString<StoredReview>(file.readText()).apply { this.file = file }
}
