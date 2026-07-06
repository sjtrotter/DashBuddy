package cloud.trotter.dashbuddy.core.database.di

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pre-open safety belt for the durable database (#690, the #314 follow-up).
 *
 * Since [cloud.trotter.dashbuddy.core.database.di.DatabaseModule] no longer falls back to a
 * destructive migration, an upgrade Room has no path for is a **loud crash** rather than a silent
 * wipe. That is the desired posture — but a botched *future* migration (or a corrupt DB) is still a
 * data-loss risk, and once Room opens the file it is the only copy. So immediately before Room opens
 * the database, if an upgrade is pending we snapshot the current DB files to a timestamped subdir
 * under an app-internal `db-backups/` dir, keeping only the last [KEEP]. A pre-migration snapshot
 * means even a bad migration is recoverable: the dev can restore the copy and back out.
 *
 * The `app_events` log is the analytics **source of truth** (the read-model tables are a rebuildable
 * projection of it), so protecting these files is protecting the product's history.
 *
 * **Detection.** We read SQLite's `user_version` straight from the file header (bytes 60–63, the
 * value Room stores its schema version in) without opening the DB — an O(1) header read. We snapshot
 * only when the on-disk version is *older* than the code's [DashBuddyDatabase.VERSION] (an upgrade is
 * about to run), or when the header can't be read (unknown → snapshot defensively). The common
 * no-change launch reads 4 bytes and returns, so this is not a copy-every-launch cost.
 *
 * **Failure-tolerant by construction.** A backup failure must NEVER stop the app from starting —
 * every path is wrapped, logs one WARN under a stable tag, and continues. The snapshot copies only
 * the driver's own on-device DB files; no PII is logged (version numbers + a file count only).
 */
internal object DatabaseBackup {

    private const val TAG = "DbBackup"

    /** How many historical snapshots to retain. Bounded so the prune is O(small) every launch. */
    private const val KEEP = 2

    /** Directory (under `filesDir`) that holds timestamped snapshot subdirs. */
    private const val BACKUP_DIR = "db-backups"

    /** The Room DB file plus its WAL sidecars. All three must travel together to be restorable. */
    private val FILE_SUFFIXES = listOf("", "-shm", "-wal")

    /**
     * Snapshot [dbName] (and its `-shm`/`-wal` sidecars) if the on-disk schema version is older than
     * [currentVersion]. No-op on first launch (no file yet) and on a same-version launch. Never
     * throws — a failure logs one WARN and returns so [DatabaseModule] can proceed to open the DB.
     */
    fun backupIfUpgradePending(context: Context, dbName: String, currentVersion: Int) {
        try {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists()) return // first launch — nothing to protect yet

            val onDiskVersion = readUserVersion(dbFile)
            // Skip only when we KNOW the on-disk schema already matches (or exceeds) the code. An
            // unreadable header (null) falls through to a defensive snapshot.
            if (onDiskVersion != null && onDiskVersion >= currentVersion) return

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
            val backupsRoot = File(context.filesDir, BACKUP_DIR)
            val dest = File(backupsRoot, stamp)
            if (!dest.mkdirs() && !dest.isDirectory) {
                Timber.tag(TAG).w("could not create backup dir %s; skipping snapshot", dest.path)
                return
            }

            var copied = 0
            for (suffix in FILE_SUFFIXES) {
                val src = File(dbFile.parentFile, dbName + suffix)
                if (src.exists()) {
                    src.copyTo(File(dest, dbName + suffix), overwrite = true)
                    copied++
                }
            }

            prune(backupsRoot)

            Timber.tag(TAG).i(
                "pre-migration DB snapshot taken (onDisk=%s → code=%d): %d file(s) → %s",
                onDiskVersion?.toString() ?: "unknown",
                currentVersion,
                copied,
                dest.name,
            )
        } catch (t: Throwable) {
            // The belt must never prevent startup. Log and continue to open the DB.
            Timber.tag(TAG).w(t, "pre-migration DB backup failed; continuing to open DB")
        }
    }

    /**
     * Read SQLite's `user_version` from the file header (big-endian int at byte offset 60) without
     * opening the DB. Returns null if the file is too short or unreadable.
     */
    private fun readUserVersion(dbFile: File): Int? = try {
        RandomAccessFile(dbFile, "r").use { raf ->
            if (raf.length() < 64) return null
            raf.seek(60)
            val b = ByteArray(4)
            raf.readFully(b)
            ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "could not read on-disk DB user_version; will snapshot defensively")
        null
    }

    /** Keep only the [KEEP] newest snapshot subdirs (lexical == chronological for the stamp format). */
    private fun prune(backupsRoot: File) {
        val dirs = backupsRoot.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?: return
        if (dirs.size <= KEEP) return
        for (old in dirs.dropLast(KEEP)) {
            old.deleteRecursively()
        }
    }
}
