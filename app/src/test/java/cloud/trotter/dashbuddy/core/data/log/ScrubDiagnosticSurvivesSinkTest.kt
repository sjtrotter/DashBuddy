package cloud.trotter.dashbuddy.core.data.log

import android.util.Log
import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.test.util.RecordingTree
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import timber.log.Timber

/**
 * #862 — end-to-end proof across the module seam: the WARN the capture layer emits when the
 * privacy layer fires reaches `shareable.log` **intact**, instead of redacting itself to
 * `[scrubbed:Transfer out]`.
 *
 * `:core:pipeline` emits the diagnostic; `:core:data` owns the fail-closed sink; only `:app` sees
 * both, so this is where the real emitter meets the real sink with the REAL production scrubber
 * binding (`SensitiveTextMarkers::findMarker`, exactly what `AppModule` binds). The message is
 * never hand-written here — it comes out of [CaptureWriter] — so a later reword of the WARN cannot
 * silently make this test pass against a line the app no longer emits.
 *
 * FAILS on the pre-#862 code (the diagnostic named the marker verbatim → the sink ate the line).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScrubDiagnosticSurvivesSinkTest {

    /** The production binding: one marker SSOT, no allowlist. */
    private val realScrubber = LogScrubber { SensitiveTextMarkers.findMarker(it) }

    private object SinkBus : CaptureBus {
        override fun offer(
            captureId: String,
            source: String,
            classification: String?,
            platform: String,
            envelopeJson: String,
            contentHash: Int?,
        ): String = captureId
    }

    private object NoRedact : ScreenRedactionSource {
        override fun redactFor(ruleId: String): CompiledRedact? = null
    }

    /** Drive the real UNKNOWN-click sensitive drop and return the WARN it emitted. */
    private fun emitScrubDiagnostic(): String {
        val recording = RecordingTree()
        Timber.plant(recording)
        try {
            CaptureWriter(SinkBus, PipelineStats(), NoRedact).captureClick(
                Observation.Click(
                    timestamp = 1_000L, captureId = null, ruleId = null,
                    metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
                    parsed = ParsedFields.None, target = UNKNOWN_TARGET,
                ),
                PipelineEvent.Click(
                    timestamp = 1_000L,
                    node = UiNode(text = "Transfer out", isClickable = true),
                    packageName = "com.doordash.driverapp",
                ),
                screenTarget = null,
            )
        } finally {
            Timber.uproot(recording)
        }
        val warn = recording.records.firstOrNull { it.message.startsWith("Capture scrubbed:") }
        assertNotNull("CaptureWriter emitted no scrub diagnostic — fixture/wiring broken", warn)
        return warn!!.message
    }

    /** Wrap a message the way the production tree formats a line for the sinks. */
    private fun asLogLine(message: String) =
        "2026-07-24 21:39:30.000 [Idle] WARN/Pipeline: $message\n"

    @Test
    fun `the capture-scrub diagnostic reaches the shareable log intact`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        val message = emitScrubDiagnostic()
        repo.appendLog(asLogLine(message), Log.WARN)
        advanceUntilIdle()

        val share = repo.shareableLogContents()
        assertTrue("the diagnostic was eaten by the sink: <$share>", share.contains(message))
        assertFalse("the diagnostic was redacted", share.contains("[scrubbed:"))
        assertEquals(0, repo.autoScrubbedLineCount)
    }

    @Test
    fun `a third-party line carrying the same marker is still scrubbed - sink stays fail-closed`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = LogRepository(RuntimeEnvironment.getApplication(), dispatcher, realScrubber)

        // Same marker word, but as scanned third-party content — must NOT survive.
        repo.appendLog(asLogLine("SECRETSTORE Transfer out \$152.10"), Log.WARN)
        advanceUntilIdle()

        val share = repo.shareableLogContents()
        assertFalse(share.contains("SECRETSTORE"))
        assertTrue(share.contains("[scrubbed:Transfer out]"))
        assertEquals(1, repo.autoScrubbedLineCount)
    }
}
