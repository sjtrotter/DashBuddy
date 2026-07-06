package cloud.trotter.dashbuddy.core.database.di

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * #690 review — unit coverage for [DatabaseBackup] (the pre-open safety belt). Runs under Robolectric
 * for a real [Context] (getDatabasePath/filesDir) and a real [SQLiteDatabase] so we can set an
 * on-disk `user_version` and exercise the WAL-aware read. This is a **unit** test — it gates PR CI
 * (unlike the instrumented `MigrationTest`).
 *
 * Covers: a version MISMATCH (upgrade AND downgrade) takes a snapshot; a same-version launch skips;
 * the dedup marker skips a second identical snapshot; and prune keeps [KEEP] total including the
 * just-created dir and never deletes it.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseBackupTest {

    private lateinit var context: Context
    private val dbName = "backup-test.db"
    private val backupsRoot: File
        get() = File(context.filesDir, "db-backups")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clean()
    }

    @After
    fun tearDown() = clean()

    private fun clean() {
        backupsRoot.deleteRecursively()
        for (suffix in listOf("", "-shm", "-wal")) {
            context.getDatabasePath(dbName + suffix).delete()
        }
    }

    /** Create a real SQLite DB file at [version] with one table + row so it has non-trivial length. */
    private fun createDbAtVersion(version: Int) {
        val f = context.getDatabasePath(dbName)
        f.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(f, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS t(x TEXT)")
            db.execSQL("INSERT INTO t(x) VALUES ('hello')")
            db.version = version
        }
    }

    private fun snapshotDirs(): List<File> =
        backupsRoot.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()

    @Test
    fun versionMismatch_upgrade_takesSnapshot() {
        createDbAtVersion(9)

        DatabaseBackup.backupIfUpgradePending(context, dbName, currentVersion = 10)

        val dirs = snapshotDirs()
        assertEquals("one snapshot expected on an upgrade mismatch", 1, dirs.size)
        val dir = dirs.single()
        assertTrue("dir is version-prefixed with the ON-DISK version", dir.name.startsWith("v9-"))
        assertTrue("db file copied", File(dir, dbName).isFile)
        assertTrue("dedup marker written", File(dir, "source.meta").isFile)
    }

    @Test
    fun versionMismatch_downgrade_takesSnapshot() {
        // On-disk is NEWER than the code (dev sideloads an older APK to bisect) — must still snapshot
        // before Room's loud downgrade throw.
        createDbAtVersion(11)

        DatabaseBackup.backupIfUpgradePending(context, dbName, currentVersion = 10)

        val dirs = snapshotDirs()
        assertEquals("one snapshot expected on a downgrade mismatch", 1, dirs.size)
        assertTrue("dir prefixed with the on-disk (newer) version", dirs.single().name.startsWith("v11-"))
    }

    @Test
    fun sameVersion_skips() {
        createDbAtVersion(10)

        DatabaseBackup.backupIfUpgradePending(context, dbName, currentVersion = 10)

        assertFalse("no backups dir on a same-version launch", backupsRoot.exists() && snapshotDirs().isNotEmpty())
    }

    @Test
    fun dedupMarker_skipsSecondIdenticalSnapshot() {
        createDbAtVersion(9)

        DatabaseBackup.backupIfUpgradePending(context, dbName, currentVersion = 10)
        val dir = snapshotDirs().single()
        val copied = File(dir, dbName)
        assertTrue(copied.isFile)

        // Remove the copied DB but KEEP the marker. A second call against the UNCHANGED source must
        // dedup on the marker and return BEFORE copying — so the removed file stays absent. (If dedup
        // were broken it would re-copy, since same-second timestamp reuses the dir name.)
        assertTrue(copied.delete())
        DatabaseBackup.backupIfUpgradePending(context, dbName, currentVersion = 10)

        assertFalse("dedup should have skipped the re-copy", copied.isFile)
        assertEquals("still exactly one snapshot dir", 1, snapshotDirs().size)
    }

    @Test
    fun prune_keepsKeepIncludingJustCreated_andNeverDeletesJustCreated() {
        createDbAtVersion(9)

        // Three pre-existing snapshot dirs with distinct OLD mtimes and a marker that will NOT match
        // the current source (so dedup does not short-circuit the new snapshot).
        val old1 = mkOldSnapshot("v1-old1", mtime = 1_000L)
        val old2 = mkOldSnapshot("v1-old2", mtime = 2_000L)
        val old3 = mkOldSnapshot("v1-old3", mtime = 3_000L)

        DatabaseBackup.backupIfUpgradePending(context, dbName, currentVersion = 10)

        val dirs = snapshotDirs()
        assertEquals("prune keeps KEEP=2 total", 2, dirs.size)
        val justCreated = dirs.firstOrNull { it.name.startsWith("v9-") }
        assertNotNull("the just-created snapshot must survive prune", justCreated)
        // The two oldest fakes are evicted; the newest fake (old3) is the other survivor.
        assertTrue("newest surviving fake is old3", dirs.any { it == old3 })
        assertFalse("oldest fake evicted", old1.exists())
        assertFalse("2nd-oldest fake evicted", old2.exists())
    }

    private fun mkOldSnapshot(name: String, mtime: Long): File {
        val d = File(backupsRoot, name)
        d.mkdirs()
        File(d, "source.meta").writeText("version=1 length=1 mtime=1")
        d.setLastModified(mtime)
        return d
    }
}
