package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** #1615: the start-time swap is all or nothing, and a stage without its `ready` marker is never applied. */
@RunWith(RobolectricTestRunner::class)
class RestoreSwapTest {

    private lateinit var context: Context
    private val db = byteArrayOf(1)
    private val wal = byteArrayOf(2)
    private val vehicles = byteArrayOf(3)
    private val stagedDb = byteArrayOf(4)
    private val stagedVehicles = byteArrayOf(5)

    private val staging get() = File(context.filesDir, BackupFormat.STAGING_DIR)
    private val previous get() = File(context.filesDir, BackupFormat.PREVIOUS_DIR)
    private val dbFile get() = context.getDatabasePath(GigLogDatabase.NAME)
    private val walFile get() = File(dbFile.path + "-wal")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BackupTestFiles.clean(context)
        dbFile.parentFile?.mkdirs()
        dbFile.writeBytes(db)
        walFile.writeBytes(wal)
        BackupTestFiles.writeStore(context, DataStoreFiles.VEHICLES, vehicles)
        File(staging, "datastore").mkdirs()
        File(staging, BackupFormat.DB_ENTRY).writeBytes(stagedDb)
        File(staging, BackupFormat.datastoreEntry(DataStoreFiles.VEHICLES)).writeBytes(stagedVehicles)
    }

    @After
    fun tearDown() = BackupTestFiles.clean(context)

    private fun assertCurrentUnchanged() {
        assertArrayEquals(db, dbFile.readBytes())
        assertArrayEquals(wal, walFile.readBytes())
        assertArrayEquals(vehicles, BackupTestFiles.readStore(context, DataStoreFiles.VEHICLES))
    }

    @Test
    fun `a failed third move puts every file back and records the failure`() = runBlocking {
        File(staging, BackupFormat.READY_FILE).writeText("123")
        var moves = 0
        RestoreSwap.applyPending(context) { from, to -> if (++moves == 3) false else from.renameTo(to) }

        assertEquals(3, moves)
        assertCurrentUnchanged()
        assertEquals("nothing left in previous", emptyList<File>(), previous.walkTopDown().filter { it.isFile }.toList())
        assertFalse("the stage is deleted", staging.exists())
        assertEquals(RestoreOutcome.Failed, BackupRestorer(context).takeResult())
    }

    @Test
    fun `a throwing move is a failure too`() = runBlocking {
        File(staging, BackupFormat.READY_FILE).writeText("123")
        var moves = 0
        RestoreSwap.applyPending(context) { from, to ->
            if (++moves == 4) throw SecurityException("denied") else from.renameTo(to)
        }
        assertCurrentUnchanged()
        assertFalse(staging.exists())
        assertEquals(RestoreOutcome.Failed, BackupRestorer(context).takeResult())
    }

    @Test
    fun `a stale stage with no ready marker is deleted and nothing moves`() = runBlocking {
        RestoreSwap.applyPending(context)

        assertCurrentUnchanged()
        assertFalse(staging.exists())
        assertFalse(previous.exists())
        assertNull(BackupRestorer(context).takeResult())
    }

    @Test
    fun `a ready stage swaps in and the old files move to previous`() = runBlocking {
        File(staging, BackupFormat.READY_FILE).writeText("123")
        RestoreSwap.applyPending(context)

        assertArrayEquals(stagedDb, dbFile.readBytes())
        assertFalse("the old WAL never meets the restored file", walFile.exists())
        assertArrayEquals(stagedVehicles, BackupTestFiles.readStore(context, DataStoreFiles.VEHICLES))
        assertArrayEquals(db, File(previous, GigLogDatabase.NAME).readBytes())
        assertArrayEquals(wal, File(previous, GigLogDatabase.NAME + "-wal").readBytes())
        assertArrayEquals(vehicles, File(previous, BackupFormat.datastoreEntry(DataStoreFiles.VEHICLES)).readBytes())
        assertFalse(staging.exists())
        assertEquals(RestoreOutcome.Restored(123L), BackupRestorer(context).takeResult())
    }

    @Test
    fun `a second restore replaces the one previous copy`() = runBlocking {
        File(previous, "datastore").mkdirs()
        File(previous, "old-leftover").writeText("x")
        File(staging, BackupFormat.READY_FILE).writeText("123")
        RestoreSwap.applyPending(context)
        assertFalse(File(previous, "old-leftover").exists())
        assertArrayEquals(db, File(previous, GigLogDatabase.NAME).readBytes())
    }
}
