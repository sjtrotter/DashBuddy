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
 * 1. `swapping`: holds the names of the live files present before the restore (the database's `giglog.db`, `-wal`,
 *    `-shm`, `-journal` and each backed-up DataStore file), written once on the first attempt and read on every
 *    retry. Those files move to `restore-previous/`. That one copy is cleared only on a fresh first attempt.
 * 2. `installing`: the staged files move into place. A store the backup lacks stays absent, so the install matches the
 *    backup.
 * Each move is a rename on one filesystem; a resumed phase moves only the files still waiting.
 *
 * A failed move starts a rollback, opened by a synced `rolling-back` marker that takes precedence at every later
 * start until the rollback finishes. The rollback is idempotent: each original still in `restore-previous/` replaces
 * whatever is live at its path, an original already moved back is left alone, and a live file at a path with no
 * original came from the backup and is deleted. Then `failed` is written and the stage deleted.
 *
 * Without the `ready` marker any leftover stage is deleted and nothing moves. It never touches the consent, grant,
 * developer, notice or census stores, logs or captures. An [Exception] never escapes; an [Error] does, as the crash it
 * is, and the next start resumes. Logging (tag `Backup`): INFO on a swap, WARN on a rollback; counts and class names.
 */
object RestoreSwap {

    private const val TAG = "Backup"
    private const val SWAPPING = "swapping"
    private const val INSTALLING = "installing"
    private const val ROLLING_BACK = "rolling-back"
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
            val swap = Swap(context, staging, move)
            if (!swap.rollingBack.exists()) {
                val swapped = try {
                    swap.run()
                    true
                } catch (e: Exception) {
                    Timber.tag(TAG).w("restore swap failed (%s), rolling back", e.javaClass.simpleName)
                    false
                }
                if (swapped) {
                    runCatching { writeResult(filesDir, "${BackupFormat.RESULT_OK} ${createdAt ?: ""}".trim()) }
                    finish(staging)
                    Timber.tag(TAG).i("restore swapped")
                    return
                }
            }
            swap.rollBack()
            runCatching { writeResult(filesDir, BackupFormat.RESULT_FAILED) }
            finish(staging)
            Timber.tag(TAG).w("restore rolled back, old data kept")
        } catch (e: Exception) {
            // The rollback could not finish. The stage stays, so the next start retries it.
            Timber.tag(TAG).w("restore rollback incomplete (%s), retried at the next start", e.javaClass.simpleName)
            runCatching { writeResult(filesDir, BackupFormat.RESULT_FAILED) }
        }
    }

    /** One restore's file plan. Keys are the names under `restore-previous/`, which double as the originals list. */
    private class Swap(context: Context, staging: File, private val move: (File, File) -> Boolean) {
        private val previous = File(context.filesDir, BackupFormat.PREVIOUS_DIR)
        private val swapping = File(staging, SWAPPING)
        private val installing = File(staging, INSTALLING)
        val rollingBack = File(staging, ROLLING_BACK)

        /** Every live path a restore may replace, by key. */
        private val live: Map<String, File> = run {
            val dbFile = context.getDatabasePath(GigLogDatabase.NAME)
            DB_SUFFIXES.associate { GigLogDatabase.NAME + it to File(dbFile.path + it) } +
                DataStoreFiles.BACKED_UP.sorted().associate {
                    BackupFormat.datastoreEntry(it) to BackupFormat.datastoreFile(context, it)
                }
        }

        /** Each staged file and the key of the live path it replaces (the staged database is `giglog.db`). */
        private val staged: List<Pair<File, String>> = listOf(File(staging, BackupFormat.DB_ENTRY) to GigLogDatabase.NAME) +
            DataStoreFiles.BACKED_UP.sorted().map { BackupFormat.datastoreEntry(it) }.map { File(staging, it) to it }

        fun run() {
            if (!installing.exists()) {
                if (!swapping.exists()) {
                    previous.deleteRecursively()
                    val originals = live.filterValues { it.exists() }.keys
                    writeMarker(swapping, originals.joinToString("\n"))
                }
                for (key in originals()) moveIfWaiting(live.getValue(key), File(previous, key))
                writeMarker(installing, "")
            }
            for ((from, key) in staged) moveIfWaiting(from, live.getValue(key))
        }

        /** Puts every original back; idempotent, so a death part way finishes at the next start. */
        fun rollBack() {
            if (!rollingBack.exists()) writeMarker(rollingBack, "")
            // No swapping marker: phase 1 never began and nothing moved.
            if (!swapping.exists()) return
            val originals = originals()
            var notPutBack = 0
            for ((key, path) in live) {
                val kept = File(previous, key)
                when {
                    key in originals && kept.exists() -> {
                        if (path.exists()) path.delete()
                        if (!move(kept, path)) notPutBack++
                    }
                    key !in originals && path.exists() -> if (!path.delete()) notPutBack++
                }
            }
            if (notPutBack > 0) throw IOException("$notPutBack file(s) not put back")
        }

        private fun originals(): Set<String> =
            swapping.readLines().map { it.trim() }.filter { it in live.keys }.toSet()

        private fun moveIfWaiting(from: File, to: File) {
            if (!from.exists()) return
            to.parentFile?.mkdirs()
            if (to.exists() || !move(from, to)) throw IOException("a restore move failed")
        }

        /** Writes a marker whole or not at all: a synced temp file renamed into place. */
        private fun writeMarker(marker: File, text: String) {
            val tmp = File(marker.path + ".tmp")
            BackupFormat.writeSynced(tmp, text.toByteArray(Charsets.UTF_8))
            if (!tmp.renameTo(marker)) throw IOException("could not write a restore marker")
        }
    }

    /**
     * Ends a stage: `ready` goes first, in one delete, so a death while the rest is deleted leaves an inert stage that
     * the next start removes, never a half-deleted one it would act on.
     */
    private fun finish(staging: File) {
        File(staging, BackupFormat.READY_FILE).delete()
        staging.deleteRecursively()
    }

    private fun writeResult(filesDir: File, text: String) {
        File(filesDir, BackupFormat.RESULT_FILE).writeText(text)
    }
}
