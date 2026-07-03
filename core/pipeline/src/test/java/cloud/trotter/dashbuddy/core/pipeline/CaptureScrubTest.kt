package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * #432 — UNKNOWN captures are scrubbed by the fail-closed text backstop:
 * an unrecognized sensitive screen must never reach the CaptureBus, while
 * benign UNKNOWNs still capture for triage.
 */
class CaptureScrubTest {

    private val captureBus: CaptureBus = mock()
    private val stats = PipelineStats()
    private val writer = CaptureWriter(captureBus, stats)

    private fun screenEvent(tree: UiNode) = PipelineEvent.Screen(
        timestamp = 1_000L,
        tree = tree,
        snapshot = TreeSnapshot(
            tree = tree,
            packageName = "com.doordash.driverapp",
            source = TreeSnapshot.Source.STATE_CHANGED,
        ),
        packageName = "com.doordash.driverapp",
    )

    private fun unknownObs() = Observation.Screen(
        timestamp = 1_000L,
        captureId = null,
        ruleId = null,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = UNKNOWN_TARGET,
    )

    @Test
    fun `UNKNOWN screen with sensitive text is never offered to the capture bus`() {
        val toxic = UiNode(children = listOf(UiNode(text = "Available balance: \$152.10")))
        writer.captureScreen(unknownObs(), screenEvent(toxic))

        verify(captureBus, never()).offer(any(), any(), any(), any(), any(), any())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `benign UNKNOWN screen still captures for triage`() {
        val benign = UiNode(children = listOf(UiNode(text = "Some new promo screen")))
        writer.captureScreen(unknownObs(), screenEvent(benign))

        verify(captureBus, times(1)).offer(any(), any(), any(), any(), any(), any())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `recognized screens are not scrubbed - the rule gate already vetted them`() {
        val tree = UiNode(children = listOf(UiNode(text = "ending in 1234")))
        val recognized = unknownObs().copy(ruleId = "doordash.screen.test", target = "idle_map")
        writer.captureScreen(recognized, screenEvent(tree))

        verify(captureBus, times(1)).offer(any(), any(), any(), any(), any(), any())
        assertTrue(stats.scrubbedUnknownCaptureCount == 0L)
    }

    // #597: clicks now always persist (no bus dedup) — so the click path needs
    // the same fail-closed backstop: an UNKNOWN click on an unruled sensitive
    // surface must never persist the tapped node's text.

    private fun unknownClickObs() = Observation.Click(
        timestamp = 1_000L,
        captureId = null,
        ruleId = null,
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = UNKNOWN_TARGET,
    )

    @Test
    fun `UNKNOWN click with sensitive text is never offered to the capture bus`() {
        val toxic = UiNode(text = "Available balance: \$152.10", isClickable = true)
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = toxic, packageName = "com.doordash.driverapp"),
            screenTarget = null,
        )

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1L, stats.scrubbedUnknownCaptureCount)
    }

    @Test
    fun `benign UNKNOWN click still captures for triage`() {
        val benign = UiNode(text = "Some new button", isClickable = true)
        writer.captureClick(
            unknownClickObs(),
            PipelineEvent.Click(timestamp = 1_000L, node = benign, packageName = "com.doordash.driverapp"),
            screenTarget = null,
        )

        verify(captureBus, times(1)).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(0L, stats.scrubbedUnknownCaptureCount)
    }
}
