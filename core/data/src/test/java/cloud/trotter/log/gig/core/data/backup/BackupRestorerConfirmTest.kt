package cloud.trotter.log.gig.core.data.backup

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** #1615: the `ready` marker appears whole or not at all. */
@RunWith(RobolectricTestRunner::class)
class BackupRestorerConfirmTest {

    private lateinit var context: Context
    private val staging get() = File(context.filesDir, BackupFormat.STAGING_DIR)
    private val ready get() = File(staging, BackupFormat.READY_FILE)
    private val pending get() = File(staging, "ready.tmp")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BackupTestFiles.clean(context)
        staging.mkdirs()
        File(staging, BackupFormat.DB_ENTRY).writeBytes(byteArrayOf(1))
        File(staging, BackupFormat.MANIFEST_ENTRY).writeBytes(BackupTestFiles.manifest())
    }

    @After
    fun tearDown() = BackupTestFiles.clean(context)

    @Test
    fun `confirm writes the marker through a temp file`() = runBlocking {
        assertTrue(BackupRestorer(context).confirm())
        assertTrue(ready.isFile)
        assertFalse(pending.exists())
    }

    @Test
    fun `a failed write leaves no marker`() = runBlocking {
        File(pending, "blocker").apply { parentFile?.mkdirs() }.writeText("x")
        assertFalse(BackupRestorer(context).confirm())
        assertFalse(ready.exists())
        assertFalse(pending.exists())
    }

    @Test
    fun `a failed rename leaves no marker`() = runBlocking {
        File(ready, "blocker").apply { parentFile?.mkdirs() }.writeText("x")
        assertFalse(BackupRestorer(context).confirm())
        assertFalse(ready.exists())
        assertFalse(pending.exists())
    }
}
