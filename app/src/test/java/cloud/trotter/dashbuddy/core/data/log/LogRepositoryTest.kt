package cloud.trotter.dashbuddy.core.data.log

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
import java.io.File

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
