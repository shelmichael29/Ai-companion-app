package com.aicarcompanion.app.data.knowledge

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Installs the offline knowledge pack into [dir] as `penelope_knowledge_<sha12>.db`. No Android
 * imports, so the file handling is unit-tested on the plain JVM.
 *
 * Safe against concurrent installers in one process (a process-wide lock covers prepare, copy,
 * rename and cleanup), against stale or truncated leftovers (an existing target is reused only if
 * its size matches the manifest and [isUsableDb] accepts it; otherwise it is replaced once) and
 * against half-written files (the copy goes to a unique temp file that is renamed only after the
 * SHA-256 matches). Older packs are removed only after the new one is verified and in place, and
 * the target itself is never deleted by cleanup.
 *
 * [expectedBytes] is the manifest's `database_bytes`, or negative when the manifest lacks it.
 * [VirtualMachineError] (e.g. OutOfMemoryError) is never converted into [Outcome.Failed].
 */
class KnowledgePackInstaller(
    private val dir: File,
    private val expectedSha: String,
    private val expectedBytes: Long,
    private val openPack: () -> InputStream,
    private val isUsableDb: (File) -> Boolean = { hasSqliteHeader(it) },
    private val onStage: (InstallStage) -> Unit = {},
) {

    sealed interface Outcome {
        data class Ready(val target: File) : Outcome
        data class Failed(val stage: InstallStage, val error: Exception) : Outcome
    }

    val target: File = File(dir, "$PREFIX${expectedSha.take(12)}$SUFFIX")

    /** Bytes copied so far by the current/last install; -1 before the copy starts. For diagnostics only. */
    @Volatile var copied = -1L
        private set

    /** The temp file of the current/last install, if one was created. For diagnostics only. */
    @Volatile var temp: File? = null
        private set

    @Volatile var stage = InstallStage.DIR
        private set

    fun install(): Outcome = synchronized(LOCK) {
        copied = -1L
        temp = null
        try {
            enter(InstallStage.DIR)
            check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "Couldn't create knowledge folder" }
            deleteStaleTemps()
            if (target.exists()) {
                if (isReusable()) return Outcome.Ready(target)
                enter(InstallStage.CLEANUP)
                check(target.delete() || !target.exists()) { "Couldn't remove damaged knowledge pack" }
            }
            copyVerifyRename()
            // Older packs are removed only once the new one is verified and in place.
            enter(InstallStage.CLEANUP)
            cleanupOthers()
            Outcome.Ready(target)
        } catch (e: Exception) {
            Outcome.Failed(stage, e)
        }
    }

    private fun enter(s: InstallStage) {
        stage = s
        onStage(s)
    }

    private fun isReusable(): Boolean =
        (expectedBytes < 0 || target.length() == expectedBytes) && runCatching { isUsableDb(target) }.getOrDefault(false)

    private fun copyVerifyRename() {
        val tmp = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, dir)
        temp = tmp
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            enter(InstallStage.SOURCE_OPEN)
            openPack().use { input ->
                enter(InstallStage.COPY)
                copied = 0
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        copied += n
                    }
                }
            }
            enter(InstallStage.VERIFY)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expectedSha) { "Knowledge pack checksum mismatch" }
            check(expectedBytes < 0 || tmp.length() == expectedBytes) { "Knowledge pack size mismatch" }
            enter(InstallStage.RENAME)
            check(tmp.renameTo(target)) { "Couldn't install knowledge pack" }
        } finally {
            tmp.delete() // never leave a partial file behind (no space, bad checksum); no-op after the rename
        }
    }

    private fun deleteStaleTemps() {
        dir.listFiles()?.filter { it.isFile && it.name.startsWith(TEMP_PREFIX) && it.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
    }

    /** Other packs (and their SQLite side files) and stale temps; never [target]. */
    private fun cleanupOthers() {
        dir.listFiles()?.forEach { f ->
            if (f == target) return@forEach
            val n = f.name
            val otherPack = n.startsWith(PREFIX) && SIDE_SUFFIXES.any { n.endsWith(SUFFIX + it) }
            val staleTemp = n.startsWith(TEMP_PREFIX) && n.endsWith(TEMP_SUFFIX)
            if (otherPack || staleTemp) f.delete()
        }
    }

    companion object {
        const val PREFIX = "penelope_knowledge_"
        const val SUFFIX = ".db"
        private const val TEMP_PREFIX = "installing"
        private const val TEMP_SUFFIX = ".tmp"
        private const val COPY_BUFFER = 1 shl 16
        private val SIDE_SUFFIXES = listOf("", "-journal", "-wal", "-shm")
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

        /** One lock for every installer in the process, whatever instance or folder object it was built with. */
        private val LOCK = Any()

        /** Cheap, JVM-only default check; production passes a stronger one that opens the database. */
        fun hasSqliteHeader(f: File): Boolean = f.inputStream().use { input ->
            val head = ByteArray(SQLITE_MAGIC.size)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read == head.size && head.contentEquals(SQLITE_MAGIC)
        }
    }
}
