package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.rules.NoRedaction
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * #435 item 5 — pins the structural capture skip for a DISABLED bus (the release
 * `NoOpCaptureBus`, `isEnabled == false`): each `CaptureWriter` writer method must
 * return the input observation untouched WITHOUT building an envelope or calling
 * [CaptureBus.offer]. Without these pins the `if (!captureBus.isEnabled) return obs`
 * branches are untested — the class of blindness that let #770 ship green.
 *
 * (The enabled-bus behavior — full build + offer + redaction — is pinned by
 * `CaptureScrubTest` / `CaptureRedactionTest` / `NotificationRedactionTest`, whose
 * mocks stub `isEnabled = true`.)
 */
class CaptureWriterDisabledBusTest {

    private val disabledBus: CaptureBus = mock { on { isEnabled } doReturn false }
    private val stats = PipelineStats()
    private val writer = CaptureWriter(disabledBus, stats, NoRedaction)

    private fun screenObs() = Observation.Screen(
        timestamp = 1_000L,
        captureId = null,
        ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
        target = "idle_map",
    )

    @Test
    fun `disabled bus - captureScreen never offers and returns the input observation`() {
        val obs = screenObs()
        val tree = UiNode(children = listOf(UiNode(text = "Some screen")))
        val event = PipelineEvent.Screen(
            timestamp = 1_000L,
            tree = tree,
            snapshot = TreeSnapshot(tree = tree, packageName = "com.doordash.driverapp"),
            packageName = "com.doordash.driverapp",
        )

        val result = writer.captureScreen(obs, event)

        verify(disabledBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertSame("observation must pass through untouched (captureId stays null)", obs, result)
    }

    @Test
    fun `disabled bus - captureClick never offers and returns the input observation`() {
        val obs = Observation.Click(
            timestamp = 1_000L,
            captureId = null,
            ruleId = "doordash.click.test",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.ClickFields(intent = "accept"),
            target = "accept",
        )
        val event = PipelineEvent.Click(
            timestamp = 1_000L,
            node = UiNode(text = "Accept", isClickable = true),
            packageName = "com.doordash.driverapp",
        )

        val result = writer.captureClick(obs, event, screenTarget = "offer_popup")

        verify(disabledBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertSame("observation must pass through untouched (captureId stays null)", obs, result)
    }

    @Test
    fun `disabled bus - captureNotification never offers and returns the input observation`() {
        val obs = Observation.Notification(
            timestamp = 1_000L,
            captureId = null,
            ruleId = "doordash.notification.test",
            metadata = ReplayMetadata.EMPTY,
            flow = null,
            modeHint = null,
            parsed = ParsedFields.None,
            target = "new_order",
        )
        val raw = RawNotificationData(
            title = "New Delivery!", text = "New order: go to Chipotle",
            tickerText = null, bigText = null,
            packageName = "com.doordash.driverapp", postTime = 1_000L, isClearable = true,
        )

        val result = writer.captureNotification(obs, raw)

        verify(disabledBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertSame("observation must pass through untouched (captureId stays null)", obs, result)
    }
}
