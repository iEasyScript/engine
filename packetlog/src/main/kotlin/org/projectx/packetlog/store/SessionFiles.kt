package org.projectx.packetlog.store

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.UUID

/**
 * One database per capture session.
 *
 * ⛔ Sessions must not share a file. Two clients can run on one machine - two accounts, or the same
 * account on live and on a local server - and a shared database would put two writers on one file
 * and, worse, let each one's crash recovery close out the other's *live* session and re-seal its
 * spill from under it. A file per session removes the possibility rather than guarding against it:
 * one writer, one file, for the whole life of the session.
 *
 * Ownership is an OS file lock rather than a pid file, because a lock is released by the kernel when
 * the process dies however it dies - which is the case that matters, since the client is killed
 * outright often enough that clean shutdown is the exception.
 */
object SessionFiles {

    private const val SUFFIX = ".db"
    private const val UPLOADED_SUFFIX = ".uploaded"

    data class PurgeResult(val purged: Int, val bytesFreed: Long, val stillLive: Int)

    class Owned(val file: File, val uuid: UUID, private val lock: FileLock, private val handle: RandomAccessFile) :
        AutoCloseable {
        override fun close() {
            runCatching { lock.release() }
            runCatching { handle.close() }
            runCatching { lockFileFor(file).delete() }
        }
    }

    /** Everything the capture and upload paths keep on disk, sessions and credentials alike. */
    fun root(): File = File(System.getProperty("user.home"), ".projectx/packetlog")

    fun directoryFor(profile: PacketSchema.ServerProfile): File = File(root(), profile.wireName)

    /** Session databases a live writer still holds. The complement of [abandoned]. */
    fun live(directory: File): List<File> {
        val unowned = abandoned(directory).toSet()
        return all(directory).filter { it !in unowned }
    }

    fun fileFor(directory: File, uuid: UUID): File = File(directory, "$uuid$SUFFIX")

    private fun lockFileFor(database: File): File = File(database.parentFile, "${database.name}.lock")

    private fun uploadedMarkerFor(database: File): File = File(database.parentFile, "${database.name}$UPLOADED_SUFFIX")

    /** Records that the server has sealed this session, so [purgeUploaded] knows it is safe to remove. */
    fun markUploaded(database: File) {
        val marker = uploadedMarkerFor(database)
        if (!marker.exists()) marker.createNewFile()
    }

    fun isUploaded(database: File): Boolean = uploadedMarkerFor(database).exists()

    /** Claims a fresh session file. Fails rather than proceeds if the lock cannot be taken. */
    fun claim(directory: File, uuid: UUID = UUID.randomUUID()): Owned? {
        directory.mkdirs()
        val database = fileFor(directory, uuid)
        val handle = RandomAccessFile(lockFileFor(database), "rw")
        val lock = runCatching { handle.channel.tryLock() }.getOrNull()
        if (lock == null) {
            runCatching { handle.close() }
            return null
        }
        return Owned(database, uuid, lock, handle)
    }

    /**
     * Session databases in [directory] that no live process owns.
     *
     * A file whose lock can be taken is one whose writer is gone, so recovery can safely finish it.
     * A file still held by a running client is skipped - that is the whole point of the lock.
     */
    fun abandoned(directory: File): List<File> {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) } ?: return emptyList()
        return files.filter { ifUnowned(it) { true } == true }.sortedBy { it.name }
    }

    /**
     * Runs [block] while holding [database]'s lock, or returns null if a live writer holds it.
     *
     * ⛔ The only safe way to act on a session file from outside the process that writes it. Checking
     * the lock and then acting leaves a window in which a client can claim the file; holding it for
     * the duration closes that window, which matters most for the one caller that deletes.
     */
    fun <T> ifUnowned(database: File, block: () -> T): T? {
        val lockFile = lockFileFor(database)
        if (!lockFile.exists()) return block()
        return RandomAccessFile(lockFile, "rw").use { handle ->
            val lock = runCatching { handle.channel.tryLock() }.getOrNull() ?: return null
            try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /** Every session database under a profile, live or not - what the query index reads. */
    fun all(directory: File): List<File> =
        (directory.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) } ?: emptyArray())
            .sortedBy { it.name }

    /**
     * Removes a session database and everything SQLite and this object keep beside it.
     *
     * ⛔ Only for a capture the server has confirmed it holds and has sealed. Deleting the database
     * and leaving a `-wal` behind is worse than not deleting at all: the next file to take that uuid
     * would inherit a journal describing someone else's pages.
     */
    fun delete(database: File): Boolean {
        var removed = database.delete()
        for (sidecar in sidecarsOf(database)) {
            if (sidecar.exists()) removed = sidecar.delete() && removed
        }
        return removed
    }

    /**
     * Deletes `-wal`, `-shm` and lock files whose database is gone.
     *
     * They are left behind by anything that removed a database without going through [delete], and
     * they accumulate silently because nothing else ever looks at them again.
     */
    fun removeOrphanedSidecars(directory: File): Int {
        val databases = all(directory).map { it.name }.toSet()
        val files = directory.listFiles { file -> file.isFile } ?: return 0
        return files.count { file ->
            val database = databaseNameOf(file.name)
            database != null && database !in databases && file.delete()
        }
    }

    /** The database a sidecar belongs to, or null when the file is not one. */
    private fun databaseNameOf(name: String): String? =
        SIDECAR_SUFFIXES.firstNotNullOfOrNull { suffix ->
            name.takeIf { it.endsWith(suffix) }?.removeSuffix(suffix)?.takeIf { it.endsWith(SUFFIX) }
        }

    private fun sidecarsOf(database: File): List<File> =
        SIDECAR_SUFFIXES.map { File(database.parentFile, "${database.name}$it") }

    /** SQLite's journal and shared-memory files, the ownership lock [claim] takes, and the upload marker. */
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", ".lock", UPLOADED_SUFFIX)

    /**
     * Removes every session already confirmed sealed on the server, freeing the disk space
     * `deleteAfterUpload = false` leaves behind. The complement of automatic deletion: retention keeps
     * captures queryable, this is how a user reclaims the space once they no longer need them.
     *
     * Only ever removes a session [markUploaded] certified - a session with no marker was either never
     * uploaded or is still local-only, and purging it would be indistinguishable from data loss.
     */
    fun purgeUploaded(directory: File, olderThanMs: Long? = null): PurgeResult {
        val cutoff = olderThanMs?.let { System.currentTimeMillis() - it }
        var purged = 0
        var bytes = 0L
        var live = 0
        for (database in all(directory)) {
            if (!isUploaded(database)) continue
            if (cutoff != null && database.lastModified() > cutoff) continue
            val size = database.length() + sidecarsOf(database).sumOf { if (it.exists()) it.length() else 0L }
            val removed = ifUnowned(database) { delete(database) }
            if (removed == null) {
                live++
            } else if (removed) {
                purged++
                bytes += size
            }
        }
        return PurgeResult(purged, bytes, live)
    }
}
