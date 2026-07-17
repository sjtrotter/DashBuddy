package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #597 — click captures are NEVER deduped at the bus. The bus's content-hash
 * seen-set lives as long as the a11y process (days on a phone that never
 * reboots), and a repeat tap on the same button hashes identically — so
 * dedup decayed click forensics to zero within a week (37→17→1→2→0 captures/
 * day on the 06-25→30 field week), costing the click envelopes for both
 * headline incidents. Clicks are human-bounded: every physical tap persists.
 */
class ClickCaptureNoDedupTest {

    private val captureBus: CaptureBus = mock { on { isEnabled } doReturn true }
    private val writer = CaptureWriter(captureBus, PipelineStats(), NoRedaction)

    private fun clickObs(t: Long) = Observation.Click(
        timestamp = t,
        captureId = null,
        ruleId = "doordash.click.decline_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = "decline_offer",
    )

    private fun clickEvent(t: Long) = PipelineEvent.Click(
        timestamp = t,
        node = UiNode(text = "Decline", isClickable = true),
        packageName = "com.doordash.driverapp",
    )

    @Test
    fun `click offers carry a null dedup hash so the bus never suppresses them`() {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")

        // The same button tapped twice — identical node content, identical screen.
        writer.captureClick(clickObs(1_000L), clickEvent(1_000L), screenTarget = "offer_popup")
        writer.captureClick(clickObs(2_000L), clickEvent(2_000L), screenTarget = "offer_popup")

        val hashCaptor = argumentCaptor<Int?>()
        verify(captureBus, times(2)).offer(
            any(), eq("accessibility.click"), anyOrNull(), any(), any(), hashCaptor.capture(),
        )
        // Both taps reached the bus, and neither carried a dedupable hash.
        assertEquals(2, hashCaptor.allValues.size)
        hashCaptor.allValues.forEach { assertNull(it) }
    }
}
