package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Puts a confirmed restore in place at the next start (#1615). `GigLogApplication.onCreate` calls [applyPending]
 * before `super.onCreate()`, where Hilt opens the database and the DataStores, so nothing holds the files it moves.
 *
 * With a `restore-staging/ready` marker the swap runs in two phases, each opened by a synced marker in the stage so a
 * process death at any point resumes at the next start:
 * 1. `swapping`: the current database files (`giglog.db`, `-wal`, `-shm`, `-journal`) and each backed-up DataStore
 *    file move to `restore-previous/`. That one copy is cleared only on the first attempt, before the marker.
 * 2. `installing`: the staged files move into place. A store the backup lacks stays absent, so the install matches the
 *    backup.
 * Each move is a rename on one filesystem, so a file is always in exactly one place; a resumed phase moves only the
 * files still waiting. An in-process move failure undoes both phases and the app starts on its old data. Without the
 * marker any leftover stage is deleted and nothing moves.
 *
 * It never touches the consent, grant, developer, notice or census stores, logs or captures. Room opens the result as
 * usual: the pre-open snapshot and migrations handle an older backup, and a projector version change refolds the
 * read model. An [Exception] never escapes; an [Error] does, as the crash it is, and the next start resumes the swap.
 * Logging (tag `Backup`): INFO on a swap, WARN on a failed one; counts and class names only.
 */
object RestoreSwap {

    private const val TAG = "Backup"
    private const val SWAPPING = "swapping"
    private const val INSTALLING = "installing"
    private val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

    fun applyPending(context: Context) = applyPending(context) { from, to -> from.renameTo(to) }

    internal fun applyPending(context: Context, move: (File, File) -> Boolean) {
        val filesDir = context.filesDir
        val staging = File(filesDir, BackupFormat.STAGING_DIR)
        try {
            File(filesDir, BackupFormat.STAGING_TMP_DIR).deleteRecursively()
            val ready = File(staging, BackupFormat.READY_FILE)
            if (!ready.isFile) {
                staging.deleteRecursively()
                return
            }
            val createdAt = runCatching { ready.readText().trim().toLong() }.getOrNull()
            swap(context, staging, move)
            staging.deleteRecursively()
            writeResult(filesDir, "${BackupFormat.RESULT_OK} ${createdAt ?: ""}".trim())
            Timber.tag(TAG).i("restore swapped")
        } catch (e: Exception) {
            val failed = e as? SwapFailed
            Timber.tag(TAG).w(
                "restore swap failed (%s), %d file(s) not put back",
                (failed?.cause ?: e).javaClass.simpleName, failed?.notPutBack ?: 0,
            )
            runCatching { staging.deleteRecursively() }
            runCatching { writeResult(filesDir, BackupFormat.RESULT_FAILED) }
        }
    }

    private fun swap(context: Context, staging: File, move: (File, File) -> Boolean) {
        val previous = File(context.filesDir, BackupFormat.PREVIOUS_DIR)
        val dbFile = context.getDatabasePath(GigLogDatabase.NAME)
        // (live file, its place in restore-previous/)
        val toPrevious = DB_SUFFIXES.map { File(dbFile.path + it) to File(previous, GigLogDatabase.NAME + it) } +
            DataStoreFiles.BACKED_UP.sorted().map {
                BackupFormat.datastoreFile(context, it) to File(previous, BackupFormat.datastoreEntry(it))
            }
        // (staged file, its live place)
        val toLive = listOf(File(staging, BackupFormat.DB_ENTRY) to dbFile) +
            DataStoreFiles.BACKED_UP.sorted().map {
                File(staging, BackupFormat.datastoreEntry(it)) to BackupFormat.datastoreFile(context, it)
            }
        val swapping = File(staging, SWAPPING)
        val installing = File(staging, INSTALLING)
        try {
            if (!installing.exists()) {
                if (!swapping.exists()) {
                    previous.deleteRecursively()
                    BackupFormat.writeSynced(swapping, ByteArray(0))
                }
                moveWaiting(toPrevious, move)
                BackupFormat.writeSynced(installing, ByteArray(0))
            }
            moveWaiting(toLive, move)
        } catch (e: Exception) {
            // Undo phase 2, then phase 1: each file goes back to where it was before the swap began. Phase 2 starts
            // only once every live place is empty, so a live file there is an installed one; before that, none is.
            val notPutBack = (if (installing.exists()) undo(toLive) else 0) + undo(toPrevious)
            throw SwapFailed(notPutBack, e)
        }
    }

    /** Moves each pair whose source still exists; a source already moved by an earlier attempt is skipped. */
    private fun moveWaiting(pairs: List<Pair<File, File>>, move: (File, File) -> Boolean) {
        for ((from, to) in pairs) {
            if (!from.exists()) continue
            to.parentFile?.mkdirs()
            if (to.exists() || !move(from, to)) throw IOException("a restore move failed")
        }
    }

    /** Moves each pair's file back from its destination when its source is empty. Returns the moves that failed. */
    private fun undo(pairs: List<Pair<File, File>>): Int =
        pairs.count { (from, to) -> to.exists() && !from.exists() && !to.renameTo(from) }

    private fun writeResult(filesDir: File, text: String) {
        File(filesDir, BackupFormat.RESULT_FILE).writeText(text)
    }

    private class SwapFailed(val notPutBack: Int, cause: Throwable) : Exception(cause)
}
