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
 * With a `restore-staging/ready` marker: the current database files (`giglog.db`, `-wal`, `-shm`) and each backed-up
 * DataStore file move to `restore-previous/` (one copy, cleared first), then the staged files move into place. A store
 * the backup lacks stays absent, so the install matches the backup. Every move is a rename on one filesystem. A
 * failure part way moves back everything already moved; the app then starts on its old data. Without the marker any
 * leftover stage is deleted and nothing moves.
 *
 * It never touches the consent, grant, developer, notice or census stores, logs or captures. Room opens the result as
 * usual: the pre-open snapshot and migrations handle an older backup, and a projector version change refolds the
 * read model. Never throws. Logging (tag `Backup`): INFO on a swap, WARN on a failed one; app-internal names only.
 */
object RestoreSwap {

    private const val TAG = "Backup"

    fun applyPending(context: Context) = applyPending(context) { from, to -> from.renameTo(to) }

    internal fun applyPending(context: Context, move: (File, File) -> Boolean) {
        try {
            val filesDir = context.filesDir
            File(filesDir, BackupFormat.STAGING_TMP_DIR).deleteRecursively()
            val staging = File(filesDir, BackupFormat.STAGING_DIR)
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
        } catch (t: Throwable) {
            val notPutBack = (t as? SwapFailed)?.notPutBack ?: 0
            val cause = (t as? SwapFailed)?.cause ?: t
            Timber.tag(TAG).w(
                "restore swap failed (%s), %d file(s) not put back",
                cause.javaClass.simpleName, notPutBack,
            )
            runCatching { File(context.filesDir, BackupFormat.STAGING_DIR).deleteRecursively() }
            runCatching { writeResult(context.filesDir, BackupFormat.RESULT_FAILED) }
        }
    }

    /** Moves the current files to `restore-previous/` and the staged ones into place; undoes every move on failure. */
    private fun swap(context: Context, staging: File, move: (File, File) -> Boolean) {
        val previous = File(context.filesDir, BackupFormat.PREVIOUS_DIR)
        previous.deleteRecursively()
        val dbFile = context.getDatabasePath(GigLogDatabase.NAME)

        val plan = mutableListOf<Pair<File, File>>()
        for (suffix in listOf("", "-wal", "-shm")) {
            val current = File(dbFile.path + suffix)
            if (current.exists()) plan += current to File(previous, GigLogDatabase.NAME + suffix)
        }
        for (name in DataStoreFiles.BACKED_UP.sorted()) {
            val current = BackupFormat.datastoreFile(context, name)
            if (current.exists()) plan += current to File(previous, BackupFormat.datastoreEntry(name))
        }
        plan += File(staging, BackupFormat.DB_ENTRY) to dbFile
        for (name in DataStoreFiles.BACKED_UP.sorted()) {
            val staged = File(staging, BackupFormat.datastoreEntry(name))
            if (staged.isFile) plan += staged to BackupFormat.datastoreFile(context, name)
        }

        val done = ArrayList<Pair<File, File>>(plan.size)
        try {
            for ((from, to) in plan) {
                to.parentFile?.mkdirs()
                if (to.exists() || !move(from, to)) throw IOException("move ${done.size + 1} of ${plan.size} failed")
                done += from to to
            }
        } catch (t: Throwable) {
            val notPutBack = done.asReversed().count { (from, to) -> !to.renameTo(from) }
            throw SwapFailed(notPutBack, t)
        }
    }

    private class SwapFailed(val notPutBack: Int, cause: Throwable) : Exception(cause)

    private fun writeResult(filesDir: File, text: String) {
        File(filesDir, BackupFormat.RESULT_FILE).writeText(text)
    }
}
