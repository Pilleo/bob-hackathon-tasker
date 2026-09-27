package io.agentdevkit.scheduler

sealed class WriteScope {
    data class FileScope(val path: String) : WriteScope()
    data class DirScope(val path: String) : WriteScope()
    data class ResourceScope(val id: String) : WriteScope()

    fun conflictsWith(other: WriteScope): Boolean = when {
        this is FileScope && other is FileScope ->
            this.path == other.path

        this is ResourceScope && other is ResourceScope ->
            this.id == other.id

        this is DirScope && other is FileScope ->
            dirConflictsWithFile(this.path, other.path)

        this is FileScope && other is DirScope ->
            dirConflictsWithFile(other.path, this.path)

        this is DirScope && other is DirScope ->
            dirConflictsWithDir(this.path, other.path)

        // Cross-type FileScope/ResourceScope and DirScope/ResourceScope never conflict
        else -> false
    }

    private fun dirConflictsWithFile(dirPath: String, filePath: String): Boolean =
        filePath == dirPath || filePath.startsWith("$dirPath/")

    private fun dirConflictsWithDir(d1: String, d2: String): Boolean =
        d1 == d2 || d1.startsWith("$d2/") || d2.startsWith("$d1/")
}
