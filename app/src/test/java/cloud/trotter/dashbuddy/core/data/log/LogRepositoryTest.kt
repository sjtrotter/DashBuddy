package cloud.trotter.dashbuddy.core.data.log

import android.content.ContextWrapper
import android.util.Log
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.FileFilter

/**
 * #364 — log lines must land in submission order (single-writer channel).
 * #551 — two-sink split: the DEBUG firehose vs a PII-safe INFO+ shareable sink, with a
 * FAIL-CLOSED scrub AT the sink (not call-site trust). These tests are the "tested" half of
 * Principle 7's "fail-closed and tested" gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LogRepositoryTest {

    private fun logDir() =
        RuntimeEnvironment.getApplication().let { it.getExternalFilesDir(null) ?: it.filesDir }

    private fun firehose() = File(logDir(), "app.log")
    private fun shareable() = File(logDir(), "shareable.log")

    /** Real production scrubber — one marker SSOT, exactly what `:app` DI binds. */
    private val realScrubber = LogScrubber { SensitiveTextMarkers.findMarker(it) }

    @Test
    fun `internal fallback moves legacy rotations and writes new rotations under internal logs`() = runTest {
        val internal = RuntimeEnvironment.getApplication().filesDir
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalFilesDir(type: String?): File? = null
        }
        val legacy = File(internal, "app_log_rotated_20260101_000000.log").apply { writeText("legacy") }
        val externalLegacy = File(logDir(), "app_log_rotated_external.log").apply { writeText("external") }
        val payload = "x".repeat(2_500_000)
        File(internal, "app.log").writeText(payload)
        val repo = LogRepository(context, StandardTestDispatcher(testScheduler), realScrubber)
        repo.appendLog("after rotation\n")
        advanceUntilIdle()

        val rotations = File(internal, "logs")
        assertFalse(legacy.exists())
        assertEquals("legacy", File(rotations, legacy.name).readText())
        assertTrue(rotations.listFiles().orEmpty().any { it.readText() == payload })
        assertTrue(File(internal, "app.log").readText().contains("after rotation"))
        assertTrue("only the active root is scanned", externalLegacy.exists())
    }

    @Test
    fun `failed legacy renames delete disposable files and warn once even without new log lines`() = runTest {
        val rotations = File(logDir(), "logs").also { it.mkdirs() }
        val legacy = (1..2).map { index ->
            File(logDir(), "app_log_rotated_blocked_$index.log").apply {
                writeText("private debug contents")
                // A nonempty destination directory makes renameTo fail deterministically.
                File(rotations, name).mkdirs()
                File(File(rotations, name), "blocker").writeText("blocker")
            }
        }
        ShadowLog.clear()
        LogRepository(RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler), realScrubber)
        advanceUntilIdle()

        assertTrue(legacy.none { it.exists() })
        val warnings = ShadowLog.getLogsForTag("LogRepository").filter { it.type == Log.WARN }
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().msg.contains("private debug contents"))
    }

    @Test
    fun `a failed move and deletion are retried on the next initialization`() = runTest {
        val root = logDir()
        val legacy = File(root, "app_log_rotated_retry.log").apply { writeText("legacy") }
        var writable = false
        var deleteAttempts = 0
        val failingFile = object : File(legacy.path) {
            override fun renameTo(dest: File): Boolean = writable && super.renameTo(dest)
            override fun delete(): Boolean {
                deleteAttempts++
                return writable && super.delete()
            }
        }
        val activeRoot = object : File(root.path) {
            override fun listFiles(filter: FileFilter?): Array<File>? =
                if (failingFile.exists() && (filter == null || filter.accept(failingFile))) arrayOf(failingFile) else emptyArray()
        }
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getExternalFilesDir(type: String?): File = activeRoot
        }
        LogRepository(context, StandardTestDispatcher(testScheduler), realScrubber)
        advanceUntilIdle()
        assertTrue(legacy.exists())
        assertEquals(1, deleteAttempts)

        writable = true
        LogRepository(context, StandardTestDispatcher(testScheduler), realScrubber)
        advanceUntilIdle()
        assertFalse(legacy.exists())
        assertEquals("legacy", File(File(root, "logs"), legacy.name).readText())
    }

    @Test
    fun `legacy and new rotations live in the excluded logs directory and are pruned there`() = runTest {
        val legacy = File(logDir(), "app_log_rotated_20260101_000000.log")
        legacy.writeText("legacy\n")
        val rotations = File(logDir(), "logs").also { it.mkdirs() }
        repeat(50) { index ->
            File(rotations, "app_log_rotated_old_$index.log").apply {
                writeText("old\n")
                setLastModified(1_000L + index)
            }
        }
        val payload = "x".repeat(2_500_000)
        firehose().writeText(payload)
        val repo = LogRepository(
            RuntimeEnvironment.getApplication(), StandardTestDispatcher(testScheduler), realScrubber,
        )
        repo.appendLog("after rotation\n")
        advanceUntilIdle()

        assertFalse("legacy root rotations must move out of backup", legacy.exists())
        assertEquals("legacy\n", File(rotations, legacy.name).readText())
        val files = requireNotNull(rotations.listFiles())
        assertEquals(50, files.size)
        assertTrue("new rotation contains the previous firehose", files.any { it.readText() == payload })
        assertFalse(File(rotations, "app_log_rotated_old_0.log").exists())
        assertFalse(File(rotations, "app_log_rotated_old_1.log").exists())
        assertTrue(firehose().readText().contains("after rotation"))
        assertTrue(logDir().listFiles().orEmpty().none { it.name.startsWith("app_log_rotated_") })
    }

    @Test
    fun `lines land in exact submission order`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        val n = 200
        repeat(n) { i -> repo.appendLog("line-$i\n") }
        advanceUntilIdle()

        assertEquals((0 until n).map { "line-$it" }, firehose().readLines())
    }

    @Test
    fun `DEBUG line goes to firehose only, not the shareable sink`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        repo.appendLog("debug-only\n", Log.DEBUG)
        advanceUntilIdle()

        assertTrue(firehose().readText().contains("debug-only"))
        // Shareable sink saw nothing (below INFO) — file never created, contents empty.
        assertEquals("", repo.shareableLogContents())
    }

    @Test
    fun `INFO line goes to BOTH sinks when clean`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        repo.appendLog("2026-01-01 00:00:00.000 [Idle] INFO/Milestone: offer received\n", Log.INFO)
        advanceUntilIdle()

        assertTrue(firehose().readText().contains("offer received"))
        assertTrue(repo.shareableLogContents().contains("offer received"))
        assertEquals(0, repo.autoScrubbedLineCount)
    }

    @Test
    fun `INFO line containing a marker is scrubbed at the sink, verbatim in firehose`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        // Distinctive non-marker token = the "leaked" text; "Bank Account" is a real sensitive marker.
        val leaked = "2026-01-01 00:00:00.000 [Idle] INFO/Chat: SECRETSTORE Bank Account\n"
        repo.appendLog(leaked, Log.INFO)
        advanceUntilIdle()

        // Firehose keeps the DEBUG-product line verbatim.
        assertTrue(firehose().readText().contains("SECRETSTORE"))

        // Shareable sink: ONLY the redacted placeholder; the leaked token is absent from its bytes.
        val share = repo.shareableLogContents()
        assertFalse("raw leaked token must not reach the shareable file", share.contains("SECRETSTORE"))
        assertTrue(share.contains("[scrubbed:Bank Account]"))
        // Timestamp prefix survives for ordering context.
        assertTrue(share.contains("2026-01-01 00:00:00.000"))
        assertEquals(1, repo.autoScrubbedLineCount)
    }

    @Test
    fun `evasion form (NBSP inside the marker) is still scrubbed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        // NBSP (U+00A0) between the two words — a plain `contains` would miss it; normalize folds it.
        val evasion = "2026-01-01 00:00:00.000 [Idle] INFO/Chat: LEAKSTORE Bank Account\n"
        repo.appendLog(evasion, Log.INFO)
        advanceUntilIdle()

        val share = repo.shareableLogContents()
        assertFalse(share.contains("LEAKSTORE"))
        assertTrue(share.contains("[scrubbed:"))
        assertEquals(1, repo.autoScrubbedLineCount)
    }

    @Test
    fun `a throwing scrubber is treated as a hit, never verbatim`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val throwing = LogScrubber { error("boom") }
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, throwing)

        repo.appendLog("2026-01-01 00:00:00.000 [Idle] INFO/X: LEAKY payload\n", Log.INFO)
        advanceUntilIdle()

        val share = repo.shareableLogContents()
        assertFalse("throwing scrubber must not fall through to verbatim", share.contains("LEAKY"))
        assertTrue(share.contains("[scrubbed:scrubber-error]"))
        assertEquals(1, repo.autoScrubbedLineCount)
    }

    @Test
    fun `an unbound (null) scrubber writes NOTHING to the shareable sink`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // Default ctor arg is null — unbound.
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher)

        repo.appendLog("2026-01-01 00:00:00.000 [Idle] INFO/X: milestone\n", Log.INFO)
        advanceUntilIdle()

        // Firehose still receives it (firehose never depends on the scrubber).
        assertTrue(firehose().readText().contains("milestone"))
        // Fail closed: no shareable output at all.
        assertEquals("", repo.shareableLogContents())
        assertFalse(shareable().exists())
    }
}
