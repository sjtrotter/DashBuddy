package cloud.trotter.dashbuddy.core.pipeline.notification

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.settings.PlatformPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #599 adversarial F2 — pins the PIPELINE ORDERING the sensitive block rests
 * on: a sensitive-classified notification must die at the shared content gate
 * BEFORE [CaptureWriter] and before being forwarded. The classification and
 * gate behaviors are unit-tested elsewhere; this test exists so a refactor
 * that reorders `NotificationPipeline.output()` (capture above the gate)
 * cannot go green. Runs the REAL pipeline against the PRODUCTION rulesets.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationPipelineSensitiveOrderingTest {

    private val captureBus: CaptureBus = mock()
    private val platformPrefs: PlatformPreferences = mock {
        on { enabledPlatforms } doReturn MutableStateFlow(setOf<Platform>(Platform.DoorDash))
        on { enabledPackages } doReturn MutableStateFlow(setOf("com.doordash.driverapp"))
    }
    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { notificationRuleset } doReturn TestRulesetFactory.notificationRuleset
            on { isLoaded } doReturn true
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )
    private val source = NotificationSource()
    private val pipeline = NotificationPipeline(
        source = source,
        filter = NotificationFilter(platformPrefs),
        classifier = classifier,
        captureWriter = CaptureWriter(captureBus, PipelineStats()),
        platformPreferences = platformPrefs,
        stats = PipelineStats(),
    )

    private fun sbn(channelId: String, title: String, text: String): StatusBarNotification {
        val n = Notification.Builder(RuntimeEnvironment.getApplication(), channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        @Suppress("DEPRECATION")
        return StatusBarNotification(
            "com.doordash.driverapp", "com.doordash.driverapp",
            1, null, 0, 0, 0, n, Process.myUserHandle(), System.currentTimeMillis(),
        )
    }

    @Test
    fun `sensitive balance notification never reaches the capture bus or the output flow`() = runTest {
        whenever(captureBus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull()))
            .thenReturn("cap-1")
        val forwarded = mutableListOf<Observation.Notification>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { pipeline.output().collect { forwarded += it } }
        advanceUntilIdle()

        // The pledge-blocked banking notification (synthetic text/amount).
        source.emit(
            sbn(
                channelId = "dasher-notification-messages",
                title = "You're building momentum!",
                text = "Your DoorDash Crimson Savings Jar balance is now \$12.34",
            )
        )
        advanceUntilIdle()

        verify(captureBus, never()).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertTrue("sensitive notification must not be forwarded", forwarded.isEmpty())

        // CONTROL — the harness actually flows: a benign new-order notification
        // is captured AND forwarded (proves the empty assertions above are not
        // an artifact of a dead pipeline).
        source.emit(
            sbn(
                channelId = "dasher-notification-channel-new-order-v2",
                title = "New Delivery!",
                text = "New order: go to Chipotle",
            )
        )
        advanceUntilIdle()

        verify(captureBus, times(1)).offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())
        assertEquals(1, forwarded.size)
        assertEquals("new_order", forwarded.single().target)
        job.cancel()
    }
}
