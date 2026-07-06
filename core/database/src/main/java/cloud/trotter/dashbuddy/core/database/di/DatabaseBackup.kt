package cloud.trotter.dashbuddy.core.database.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pre-open safety belt for the durable database (#690, the #314 follow-up).
 *
 * Since [cloud.trotter.dashbuddy.core.database.di.DatabaseModule] no longer falls back to a
 * destructive migration, an upgrade that Room has no path for is a **loud crash** rather than a
 * silent wipe. That is the desired posture — but a botched *future* migration (or a corrupt DB) is
 * still a data-loss risk, and once Room opens the file it is the only copy. So immediately before
 * Room opens the database, if the on-disk schema version does not match the code we snapshot the
 * current DB files to a version-prefixed, timestamped subdir under an app-internal `db-backups/`
 * dir, keeping only the last [KEEP]. A pre-migration snapshot means even a bad migration is
 * recoverable: the dev can restore the copy and back out.
 *
 * The `app_events` log is the analytics **source of truth** (the read-model tables are a rebuildable
 * projection of it), so protecting these files is protecting the product's history.
 *
 * **Detection (WAL-aware).** SQLite's `user_version` (the value Room stores its schema version in)
 * lives on page 1. Under WAL journaling — Room's default — a committed page-1 change lands in the
 * `-wal` sidecar and is NOT written back to the main file's header until a checkpoint. So reading
 * byte offset 60 of the main file directly is **stale**: right after a migration commits, the header
 * still reads the OLD version until the next checkpoint, which would misclassify the very next launch
 * as "upgrade pending", take a spurious snapshot, and let [prune] rotate out the genuine
 * pre-migration copy. We therefore read the version the way SQLite resolves it — a single read-only
 * open, `[SQLiteDatabase.version]`, which reads through the WAL — and never touch the raw header.
 *
 * We snapshot whenever the on-disk version does **not equal** the code's [DashBuddyDatabase.VERSION]
 * (an upgrade OR a downgrade is about to be handled), or when the version can't be read (unknown →
 * snapshot defensively, since a corrupt DB is exactly what Android's `DefaultDatabaseErrorHandler`
 * may DELETE at open). The common no-change launch reads the version, sees a match, and returns —
 * this is not a copy-every-launch cost.
 *
 * **Bounded churn (dedup marker).** Each snapshot dir carries a `source.meta` describing the source
 * it copied (`version`/`length`/`mtime`). Before snapshotting we read the newest existing snapshot's
 * marker; if it matches the current source exactly we skip. This bounds every unbounded-copy loop a
 * mismatch-triggered snapshot could otherwise cause: a crash-loop that relaunches with an unchanged
 * file, a persistently-corrupt/unreadable header copying every launch, a downgrade crash loop.
 *
 * **Failure-tolerant by construction.** A backup failure must NEVER stop the app from starting —
 * every path is wrapped, logs one WARN under a stable tag, and continues. The snapshot copies only
 * the driver's own on-device DB files; no PII is logged (version numbers + a file count only).
 *
 * **Logging caveat.** This runs at Hilt injection time (inside `Application.super.onCreate()`),
 * before the state-aware log tree is planted. In debug builds a plain `DebugTree` is planted before
 * `super.onCreate()` so these lines surface; in release builds there is no early tree, so
 * injection-time WARN/INFO here are dropped (accepted — the crash itself is the signal).
 */
internal object DatabaseBackup {

    private const val TAG = "DbBackup"

    /** How many historical snapshots to retain (including the one just created). */
    private const val KEEP = 2

    /** Directory (under `filesDir`) that holds version-prefixed, timestamped snapshot subdirs. */
    private const val BACKUP_DIR = "db-backups"

    /** File written into each snapshot dir describing the source it copied (the dedup marker). */
    private const val META_FILE = "source.meta"

    /** The Room DB file plus its WAL sidecars. All three must travel together to be restorable. */
    private val FILE_SUFFIXES = listOf("", "-shm", "-wal")

    private val stampFormat = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
        .withZone(ZoneId.systemDefault())

    /**
     * Snapshot [dbName] (and its `-shm`/`-wal` sidecars) if the on-disk schema version does not equal
     * [currentVersion]. No-op on first launch (no file yet), on a same-version launch, and on a
     * repeat launch whose source is byte-identical to the newest snapshot (dedup marker). Never
     * throws — a failure logs one WARN and returns so [DatabaseModule] can proceed to open the DB.
     */
    fun backupIfUpgradePending(context: Context, dbName: String, currentVersion: Int) {
        try {
            val dbFile = context.getDatabasePath(dbName)
            if (!dbFile.exists()) return // first launch — nothing to protect yet

            val onDiskVersion = readUserVersion(dbFile)
            // Skip only when we KNOW the on-disk schema already matches the code. A MISMATCH in
            // either direction (upgrade or dev-sideloaded downgrade) snapshots before Room reacts;
            // an unreadable version (null) falls through to a defensive snapshot.
            if (onDiskVersion != null && onDiskVersion == currentVersion) return

            val backupsRoot = File(context.filesDir, BACKUP_DIR)

            // Dedup: if the newest existing snapshot copied an identical source, this is a repeat of
            // an already-captured state (crash loop, stuck corrupt header, downgrade loop). Skip —
            // no snapshot, no prune — so churn is bounded.
            val sourceSig = sourceSignature(onDiskVersion, dbFile)
            if (newestSnapshotMeta(backupsRoot) == sourceSig) return

            val stamp = stampFormat.format(Instant.now())
            val versionLabel = onDiskVersion?.toString() ?: "unknown"
            val dest = File(backupsRoot, "v$versionLabel-$stamp")
            dest.mkdirs() // failure here surfaces as a copyTo throw → outer catch owns it (one WARN)

            var copied = 0
            for (suffix in FILE_SUFFIXES) {
                val src = File(dbFile.parentFile, dbName + suffix)
                if (src.exists()) {
                    src.copyTo(File(dest, dbName + suffix), overwrite = true)
                    copied++
                }
            }

            // Write the dedup marker LAST so a snapshot that fails mid-copy leaves no marker and is
            // retried (rather than being mistaken for a complete capture of this source).
            File(dest, META_FILE).writeText(sourceSig)

            prune(backupsRoot, dest)

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
     * Read SQLite's `user_version` the way SQLite resolves it — a single **read-only open** that
     * reads through the WAL — rather than the raw main-file header (byte 60), which is stale until a
     * checkpoint under WAL journaling and would misclassify post-migration launches. Cheap (one open,
     * no writes). Returns null on any failure (missing/corrupt/locked → snapshot defensively).
     */
    private fun readUserVersion(dbFile: File): Int? = try {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "could not read on-disk DB user_version; will snapshot defensively")
        null
    }

    /** The dedup signature of the current DB source: version + size + mtime, on one line. */
    private fun sourceSignature(onDiskVersion: Int?, dbFile: File): String =
        "version=${onDiskVersion?.toString() ?: "unknown"} " +
            "length=${dbFile.length()} mtime=${dbFile.lastModified()}"

    /** The [META_FILE] line of the newest existing snapshot dir (by mtime), or null if none/unreadable. */
    private fun newestSnapshotMeta(backupsRoot: File): String? {
        val newest = backupsRoot.listFiles { f -> f.isDirectory }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        return try {
            File(newest, META_FILE).takeIf { it.isFile }?.readText()?.trim()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Keep the [KEEP] newest snapshot dirs (by `lastModified`, since the version prefix breaks lexical
     * ordering), including [dest]. [dest] is never a prune candidate — the just-created backup must
     * survive even a clock skew that makes an older dir look newer. Logs ONE WARN if any delete fails.
     */
    private fun prune(backupsRoot: File, dest: File) {
        val dirs = backupsRoot.listFiles { f -> f.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        val victims = dirs
            .filter { it != dest }
            .drop(KEEP - 1) // dest counts toward KEEP but is excluded above, so keep KEEP-1 others
        var anyFailed = false
        for (old in victims) {
            if (!old.deleteRecursively()) anyFailed = true
        }
        if (anyFailed) {
            Timber.tag(TAG).w("failed to fully delete one or more pruned DB snapshot dirs")
        }
    }
}
