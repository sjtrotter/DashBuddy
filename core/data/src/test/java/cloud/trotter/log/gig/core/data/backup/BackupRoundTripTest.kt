package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * #1615: a backup written from a live Room database restores over changed data. The events come back as the backup
 * held them, the crash-recovery tables come back empty, the backed-up stores come back byte for byte, a store the
 * backup lacked is absent, the consent store is untouched, and the replaced files wait in `restore-previous/`.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRoundTripTest {

    private lateinit var context: Context
    private val vehiclesAtBackup = BackupTestFiles.prefsBytes("vehicles at backup")
    private val appPrefsAtBackup = BackupTestFiles.prefsBytes("app prefs at backup")
    private val consent = byteArrayOf(9, 9, 9)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BackupTestFiles.clean(context)
    }

    @After
    fun tearDown() = BackupTestFiles.clean(context)

    private fun openRoom(): GigLogDatabase =
        Room.databaseBuilder(context, GigLogDatabase::class.java, GigLogDatabase.NAME).allowMainThreadQueries().build()

    private fun insertEvents(db: GigLogDatabase, from: Int, count: Int) {
        val sql = db.openHelper.writableDatabase
        repeat(count) { i ->
            sql.execSQL(
                "INSERT INTO app_events(aggregateId, eventType, eventPayload, occurredAt, metadata) VALUES (?, ?, ?, ?, NULL)",
                arrayOf<Any>("shift-${from + i}", "DASH_START", "{}", 1_000L + from + i),
            )
        }
    }

    @Test
    fun `a backup restores over changed data and keeps the replaced files`() = runBlocking {
        val db = openRoom()
        insertEvents(db, 0, 3)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("INSERT INTO app_state_snapshots(correlationVersion, capturedAt, sessionId, stateJson) VALUES (1, 10, 's', '{}')")
        sql.execSQL(
            "INSERT INTO observations(occurredAt, sessionId, pipelineId, ruleId, platform, flow, modeHint, parsedJson, " +
                "captureId, metadataJson, correlationVersion) VALUES (10, 's', 'p', NULL, NULL, NULL, NULL, '{}', NULL, '{}', 1)",
        )
        BackupTestFiles.writeStore(context, DataStoreFiles.VEHICLES, vehiclesAtBackup)
        BackupTestFiles.writeStore(context, DataStoreFiles.APP_PREFS, appPrefsAtBackup)
        BackupTestFiles.writeStore(context, DataStoreFiles.CONSENT_EVENT_RECEIPT, consent)

        val out = ByteArrayOutputStream()
        val summary = BackupWriter(db, context).write(out, "0.0.0-test")
        assertEquals(3L, summary.eventCount)
        assertEquals(GigLogDatabase.VERSION, summary.dbVersion)
        assertEquals(out.size().toLong(), summary.bytes)
        assertEquals(
            listOf(
                BackupFormat.MANIFEST_ENTRY,
                BackupFormat.DB_ENTRY,
                BackupFormat.datastoreEntry(DataStoreFiles.APP_PREFS),
                BackupFormat.datastoreEntry(DataStoreFiles.VEHICLES),
            ),
            entryNames(out.toByteArray()),
        )
        assertFalse("the work dir is deleted", File(context.cacheDir, "backup-work").exists())

        // The data moves on after the backup.
        insertEvents(db, 3, 2)
        BackupTestFiles.writeStore(context, DataStoreFiles.VEHICLES, byteArrayOf(7, 7))
        BackupTestFiles.writeStore(context, DataStoreFiles.APP_PREFS, byteArrayOf(8, 8))
        BackupTestFiles.writeStore(context, DataStoreFiles.WEEKLY_PLAN, byteArrayOf(6))
        db.close()

        val restorer = BackupRestorer(context)
        val staged = restorer.stage(ByteArrayInputStream(out.toByteArray()))
        assertTrue(staged.toString(), staged is StageResult.Staged)
        staged as StageResult.Staged
        assertEquals(3L, staged.eventCount)
        assertEquals("0.0.0-test", staged.appVersion)
        assertTrue(restorer.confirm())

        RestoreSwap.applyPending(context)

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(GigLogDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY,
        ).use { restored ->
            assertEquals(3L, count(restored, "app_events"))
            assertEquals(0L, count(restored, "app_state_snapshots"))
            assertEquals(0L, count(restored, "observations"))
            assertEquals(GigLogDatabase.VERSION, restored.version)
        }
        assertArrayEquals(vehiclesAtBackup, BackupTestFiles.readStore(context, DataStoreFiles.VEHICLES))
        assertArrayEquals(appPrefsAtBackup, BackupTestFiles.readStore(context, DataStoreFiles.APP_PREFS))
        assertNull("a store the backup lacked is absent", BackupTestFiles.readStore(context, DataStoreFiles.WEEKLY_PLAN))
        assertArrayEquals("consent is never touched", consent, BackupTestFiles.readStore(context, DataStoreFiles.CONSENT_EVENT_RECEIPT))

        val previous = File(context.filesDir, BackupFormat.PREVIOUS_DIR)
        assertTrue(File(previous, GigLogDatabase.NAME).isFile)
        assertArrayEquals(byteArrayOf(7, 7), File(previous, BackupFormat.datastoreEntry(DataStoreFiles.VEHICLES)).readBytes())
        assertArrayEquals(byteArrayOf(8, 8), File(previous, BackupFormat.datastoreEntry(DataStoreFiles.APP_PREFS)).readBytes())
        assertArrayEquals(byteArrayOf(6), File(previous, BackupFormat.datastoreEntry(DataStoreFiles.WEEKLY_PLAN)).readBytes())
        assertFalse(File(previous, BackupFormat.datastoreEntry(DataStoreFiles.CONSENT_EVENT_RECEIPT)).exists())
        SQLiteDatabase.openDatabase(File(previous, GigLogDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals("the replaced log had five events", 5L, count(it, "app_events")) }
        assertFalse(File(context.filesDir, BackupFormat.STAGING_DIR).exists())

        assertEquals(RestoreOutcome.Restored(staged.createdAtEpochMs), restorer.takeResult())
        assertNull("the result is read once", restorer.takeResult())

        // Room opens the restored file.
        val reopened = openRoom()
        assertEquals(3L, count(reopened, "app_events"))
        reopened.close()
    }

    @Test
    fun `a stage without confirm never swaps`() = runBlocking {
        val db = openRoom()
        insertEvents(db, 0, 1)
        val out = ByteArrayOutputStream()
        BackupWriter(db, context).write(out, "0.0.0-test")
        insertEvents(db, 1, 1)
        db.close()

        assertTrue(BackupRestorer(context).stage(ByteArrayInputStream(out.toByteArray())) is StageResult.Staged)
        RestoreSwap.applyPending(context)

        SQLiteDatabase.openDatabase(context.getDatabasePath(GigLogDatabase.NAME).path, null, SQLiteDatabase.OPEN_READONLY)
            .use { assertEquals(2L, count(it, "app_events")) }
        assertFalse(File(context.filesDir, BackupFormat.STAGING_DIR).exists())
        assertFalse(File(context.filesDir, BackupFormat.PREVIOUS_DIR).exists())
    }

    @Test
    fun `a cancelled stage is deleted`() = runBlocking {
        val db = openRoom()
        val out = ByteArrayOutputStream()
        BackupWriter(db, context).write(out, "0.0.0-test")
        db.close()
        val restorer = BackupRestorer(context)
        assertTrue(restorer.stage(ByteArrayInputStream(out.toByteArray())) is StageResult.Staged)
        restorer.discard()
        assertFalse(File(context.filesDir, BackupFormat.STAGING_DIR).exists())
        assertFalse(restorer.confirm())
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private fun count(db: GigLogDatabase, table: String): Long =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }

    private fun entryNames(zip: ByteArray): List<String> = buildList {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) add((z.nextEntry ?: break).name)
        }
    }
}
