package io.agentdevkit.planner

import java.io.File
import java.security.MessageDigest
fun FileFingerprint.matches(root: File): Boolean = boundary({ false }) {
        val file = File(root, path)
        if (!file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) false
        else if (sha256 == "MISSING") !java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
        else file.isFile && hash(file.readBytes()) == sha256
}

internal fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
