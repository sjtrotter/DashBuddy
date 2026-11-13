package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import cloud.trotter.log.gig.core.database.GigLogDatabase
import cloud.trotter.log.gig.core.datastore.DataStoreFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * #1615: a backup file is untrusted input. Each wrong or hostile file is refused with its reason, and the refusal
 * leaves the current files and an earlier stage exactly as they were, with no `ready` marker anywhere.
 */
@RunWith(RobolectricTestRunner::class)
class BackupRestorerRefusalTest {

    private lateinit var context: Context
    private val currentDb = byteArrayOf(10, 11, 12)
    private val currentStore = byteArrayOf(13, 14)
    private val earlierStage = byteArrayOf(15)

    private val staging get() = File(context.filesDir, BackupFormat.STAGING_DIR)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BackupTestFiles.clean(context)
        context.getDatabasePath(GigLogDatabase.NAME).apply { parentFile?.mkdirs() }.writeBytes(currentDb)
        BackupTestFiles.writeStore(context, DataStoreFiles.VEHICLES, currentStore)
        staging.mkdirs()
        File(staging, BackupFormat.DB_ENTRY).writeBytes(earlierStage)
    }

    @After
    fun tearDown() = BackupTestFiles.clean(context)

    private val validDb by lazy { BackupTestFiles.roomDbBytes(context) }

    private fun assertRefused(reason: RestoreRefusal, file: ByteArray, freeBytes: Long = Long.MAX_VALUE) =
        assertRefused(reason, ByteArrayInputStream(file), freeBytes)

    private fun assertRefused(reason: RestoreRefusal, input: InputStream, freeBytes: Long = Long.MAX_VALUE) = runBlocking {
        val result = BackupRestorer(context) { freeBytes }.stage(input)
        assertEquals(StageResult.Refused(reason), result)
        assertArrayEquals("current database untouched", currentDb, context.getDatabasePath(GigLogDatabase.NAME).readBytes())
        assertArrayEquals("current store untouched", currentStore, BackupTestFiles.readStore(context, DataStoreFiles.VEHICLES))
        assertArrayEquals("earlier stage untouched", earlierStage, File(staging, BackupFormat.DB_ENTRY).readBytes())
        assertFalse("no ready marker", File(staging, BackupFormat.READY_FILE).exists())
        assertEquals("only the earlier stage remains", listOf(BackupFormat.DB_ENTRY), staging.list()!!.toList())
        assertFalse("temp dir removed", File(context.filesDir, BackupFormat.STAGING_TMP_DIR).exists())
    }

    @Test
    fun `the valid fixture stages`() = runBlocking {
        val result = BackupRestorer(context) { Long.MAX_VALUE }
            .stage(ByteArrayInputStream(BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(), BackupFormat.DB_ENTRY to validDb)))
        assertEquals(StageResult.Staged(1_700_000_000_000L, "0.0.0-test", 2L), result)
        assertFalse(File(staging, BackupFormat.READY_FILE).exists())
    }

    @Test
    fun `path traversal`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(), "../${BackupFormat.DB_ENTRY}" to validDb),
    )

    @Test
    fun `leading slash`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(), "/${BackupFormat.DB_ENTRY}" to validDb),
    )

    @Test
    fun `backslash`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(), "datastore\\vehicles.preferences_pb" to byteArrayOf(1)),
    )

    @Test
    fun `unknown entry`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(),
            BackupFormat.DB_ENTRY to validDb,
            "notes.txt" to byteArrayOf(1),
        ),
    )

    @Test
    fun `a store outside the backed-up set`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(
                files = listOf(BackupFormat.DB_ENTRY, BackupFormat.datastoreEntry(DataStoreFiles.RULE_CAPABILITY_GRANTS)),
            ),
            BackupFormat.DB_ENTRY to validDb,
            BackupFormat.datastoreEntry(DataStoreFiles.RULE_CAPABILITY_GRANTS) to byteArrayOf(1),
        ),
    )

    @Test
    fun `directory entry`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(), "datastore/" to ByteArray(0), BackupFormat.DB_ENTRY to validDb),
    )

    @Test
    fun `duplicate entry`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.rawZip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(),
            BackupFormat.DB_ENTRY to validDb,
            BackupFormat.DB_ENTRY to validDb,
        ),
    )

    @Test
    fun `oversize entry`() {
        val store = BackupFormat.datastoreEntry(DataStoreFiles.VEHICLES)
        assertRefused(
            RestoreRefusal.NOT_A_BACKUP,
            BackupTestFiles.zip(
                BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(files = listOf(BackupFormat.DB_ENTRY, store)),
                BackupFormat.DB_ENTRY to validDb,
                store to ByteArray((BackupFormat.DATASTORE_CAP + 1).toInt()),
            ),
        )
    }

    @Test
    fun `oversize manifest`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to ByteArray((BackupFormat.MANIFEST_CAP + 1).toInt()), BackupFormat.DB_ENTRY to validDb),
    )

    @Test
    fun `missing manifest`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.DB_ENTRY to validDb),
    )

    @Test
    fun `unparseable manifest`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to "{not json".toByteArray(), BackupFormat.DB_ENTRY to validDb),
    )

    @Test
    fun `manifest files mismatch`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(
                files = listOf(BackupFormat.DB_ENTRY, BackupFormat.datastoreEntry(DataStoreFiles.VEHICLES)),
            ),
            BackupFormat.DB_ENTRY to validDb,
        ),
    )

    @Test
    fun `missing database`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(files = emptyList())),
    )

    @Test
    fun `format 2`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(format = 2), BackupFormat.DB_ENTRY to validDb),
    )

    @Test
    fun `a newer schema asks for an update`() {
        val newer = GigLogDatabase.VERSION + 1
        assertRefused(
            RestoreRefusal.NEWER_APP,
            BackupTestFiles.zip(
                BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(dbVersion = newer),
                BackupFormat.DB_ENTRY to BackupTestFiles.roomDbBytes(context, version = newer),
            ),
        )
    }

    @Test
    fun `a schema older than the first backup`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(dbVersion = 26),
            BackupFormat.DB_ENTRY to BackupTestFiles.roomDbBytes(context, version = 26),
        ),
    )

    @Test
    fun `a manifest version that disagrees with the database`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(dbVersion = GigLogDatabase.VERSION),
            BackupFormat.DB_ENTRY to BackupTestFiles.roomDbBytes(context, version = GigLogDatabase.VERSION - 1),
        ),
    )

    @Test
    fun `a database file that is not SQLite`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(),
            BackupFormat.DB_ENTRY to ByteArray(8192) { (it % 251).toByte() },
        ),
    )

    @Test
    fun `a SQLite file without app_events`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(),
            BackupFormat.DB_ENTRY to BackupTestFiles.sqliteBytes(context, GigLogDatabase.VERSION, withEvents = false),
        ),
    )

    @Test
    fun `a Room identity hash that is not this schema's`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(),
            BackupFormat.DB_ENTRY to BackupTestFiles.roomDbBytes(context, identityHash = "0123456789abcdef0123456789abcdef"),
        ),
    )

    @Test
    fun `a database with app_events but no Room schema`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        BackupTestFiles.zip(
            BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(),
            BackupFormat.DB_ENTRY to BackupTestFiles.sqliteBytes(context, GigLogDatabase.VERSION),
        ),
    )

    @Test
    fun `a settings file that is not a Preferences store`() {
        val store = BackupFormat.datastoreEntry(DataStoreFiles.ODOMETER_PREFS)
        assertRefused(
            RestoreRefusal.NOT_A_BACKUP,
            BackupTestFiles.zip(
                BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(files = listOf(BackupFormat.DB_ENTRY, store)),
                BackupFormat.DB_ENTRY to validDb,
                store to ByteArray(16) { 0xFF.toByte() },
            ),
        )
    }

    @Test
    fun `a valid settings file stages`() = runBlocking {
        val store = BackupFormat.datastoreEntry(DataStoreFiles.ODOMETER_PREFS)
        val result = BackupRestorer(context) { Long.MAX_VALUE }.stage(
            ByteArrayInputStream(
                BackupTestFiles.zip(
                    BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(files = listOf(BackupFormat.DB_ENTRY, store)),
                    BackupFormat.DB_ENTRY to validDb,
                    store to BackupTestFiles.prefsBytes("odometer"),
                ),
            ),
        )
        assertTrue(result.toString(), result is StageResult.Staged)
    }

    @Test
    fun `an app version that is not a short version string`() {
        for (version in listOf("", "1.0 beta", "a".repeat(65), "1.0\n2")) {
            assertRefused(
                RestoreRefusal.NOT_A_BACKUP,
                BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(appVersion = version), BackupFormat.DB_ENTRY to validDb),
            )
        }
    }

    @Test
    fun `a file that is not a zip`() = assertRefused(
        RestoreRefusal.NOT_A_BACKUP,
        "plain text, not a zip".toByteArray(),
    )

    @Test
    fun `too little free space`() = assertRefused(
        RestoreRefusal.NO_SPACE,
        BackupTestFiles.zip(BackupFormat.MANIFEST_ENTRY to BackupTestFiles.manifest(), BackupFormat.DB_ENTRY to validDb),
        freeBytes = 0,
    )

    @Test
    fun `a read error`() = assertRefused(
        RestoreRefusal.READ_FAILED,
        object : InputStream() {
            override fun read(): Int = throw IOException("unreadable")
        },
    )

    @Test
    fun `a refusal never leaves a marker the swap would apply`() {
        assertRefused(RestoreRefusal.NOT_A_BACKUP, BackupTestFiles.zip(BackupFormat.DB_ENTRY to validDb))
        RestoreSwap.applyPending(context)
        assertArrayEquals(currentDb, context.getDatabasePath(GigLogDatabase.NAME).readBytes())
        assertTrue("the unconfirmed stage is deleted", !staging.exists())
    }
}
