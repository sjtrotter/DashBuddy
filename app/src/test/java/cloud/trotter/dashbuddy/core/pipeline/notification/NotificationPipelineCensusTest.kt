package cloud.trotter.dashbuddy.core.pipeline.notification

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import cloud.trotter.census.contract.NotifTextField
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.core.pipeline.CaptureWriter
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.PlatformAppVersions
import cloud.trotter.dashbuddy.core.pipeline.census.NotificationSkeletonBuilder.Refusal
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonPublisher
import cloud.trotter.dashbuddy.core.pipeline.notification.input.NotificationSource
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.core.pipeline.rules.ScreenRedactionSource
import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.capture.CensusSink
import cloud.trotter.dashbuddy.domain.capture.NoOpCensusEnvelopeSink
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationPipelineCensusTest {
    private class Harness(captureEnabled: Boolean = true, captureAccepted: Boolean = true, sinkEnabled: Boolean = true, sinkFailure: Throwable? = null) {
        val stats = PipelineStats()
        val order = mutableListOf<String>()
        val records = mutableListOf<CensusRecord>()
        val platforms = MutableStateFlow(setOf(Platform.DoorDash, Platform.Uber))
        val prefs: PlatformPreferences = mock {
            on { enabledPlatforms } doReturn platforms
            on { enabledPackages } doReturn MutableStateFlow(Platform.watchedPackages)
        }
        val bus: CaptureBus = mock { on { isEnabled } doReturn captureEnabled }
        val interpreter: JsonRuleInterpreter = mock {
            on { notificationRuleset } doReturn TestRulesetFactory.notificationRuleset
            on { isLoaded } doReturn true
        }
        val source = NotificationSource()
        val sink = object : CensusSink {
            override val isEnabled = sinkEnabled
            override val acceptedSchemaIds = cloud.trotter.census.contract.CensusSkeletonSchema.SUPPORTED_SCHEMA_IDS.toSet()
            override fun offer(record: CensusRecord): Boolean {
                order += "census"
                sinkFailure?.let { throw it }
                records += record
                return true
            }
        }
        init {
            whenever(bus.offer(any(), any(), anyOrNull(), any(), any(), anyOrNull())).thenAnswer {
                order += "capture"
                if (captureAccepted) "capture-after-admission" else null
            }
        }
        val pipeline = NotificationPipeline(source, NotificationFilter(prefs),
            ObservationClassifier(interpreter, mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY }, PlatformAppVersions.NONE),
            CaptureWriter(bus, stats, object : ScreenRedactionSource { override fun redactFor(ruleId: String) = null }),
            prefs, stats, SkeletonPublisher(sink, stats, NoOpCensusEnvelopeSink))
    }

    private fun push(title: String = "Continue", action: String? = null, platform: Platform = Platform.DoorDash,
        channel: String = "synthetic-census"): StatusBarNotification {
        val builder = Notification.Builder(RuntimeEnvironment.getApplication(), channel)
            .setContentTitle(title).setSmallIcon(android.R.drawable.ic_dialog_info)
        action?.let { builder.addAction(Notification.Action.Builder(null, it, null).build()) }
        val packageName = requireNotNull(platform.packageName)
        @Suppress("DEPRECATION")
        return StatusBarNotification(packageName, packageName, 1, null, 0, 0, 0,
            builder.build(), Process.myUserHandle(), 1_790_726_400_000L)
    }

    @Test fun `admitted UNKNOWN publishes after capture before drop and repeated push dedups`() = runTest {
        val h = Harness()
        val forwarded = mutableListOf<Observation.Notification>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.pipeline.output().collect { forwarded += it } }
        h.source.emit(push()); advanceUntilIdle()
        h.source.emit(push()); advanceUntilIdle()
        assertEquals(listOf("capture", "census"), h.order)
        assertEquals("capture-after-admission", h.records.single().captureId)
        assertTrue(forwarded.isEmpty())
        assertEquals(1L, h.stats.censusNotificationCount())
        assertEquals(1L, h.stats.suppressedDuplicateCount)
    }

    @Test fun `capture disabled or refusing still publishes benign and independently refuses action markers`() = runTest {
        for ((enabled, accepted) in listOf(false to false, true to false, true to true)) {
            val h = Harness(enabled, accepted)
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.pipeline.output().collect {} }
            h.source.emit(push("Deliver to Jordan")); advanceUntilIdle()
            val item = h.records.single()
            assertEquals(if (enabled && accepted) "capture-after-admission" else null, item.captureId)
            assertEquals(TextSlot.WITHHELD, NotificationSkeletonSchema.deserialize(item.skeletonJson).slots.getValue(NotifTextField.TITLE))
            // Distinct body avoids action-insensitive content dedup, so the refusal gate is exercised.
            h.source.emit(push("Next", action = "Transfer out")); advanceUntilIdle()
            assertEquals(1, h.records.size)
            assertEquals(1L, h.stats.censusNotificationRefusedCount(Refusal.SENSITIVE_NOTIFICATION))
            job.cancel()
        }
    }

    @Test fun `pre readiness disabled platforms and recognized notifications never publish`() = runTest {
        val h = Harness()
        val forwarded = mutableListOf<Observation.Notification>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.pipeline.output().collect { forwarded += it } }
        whenever(h.interpreter.isLoaded).thenReturn(false)
        h.source.emit(push()); advanceUntilIdle()
        whenever(h.interpreter.isLoaded).thenReturn(true)
        h.platforms.value = emptySet()
        h.source.emit(push()); advanceUntilIdle()
        assertTrue(h.order.isEmpty())
        h.platforms.value = setOf(Platform.DoorDash)
        h.source.emit(push("New order", channel = "dasher-notification-channel-new-order-v2")); advanceUntilIdle()
        assertEquals(1, forwarded.size)
        assertTrue(h.records.isEmpty())
    }

    @Test fun `disabled census leaves recognized forwarding and capture intact`() = runTest {
        val h = Harness(sinkEnabled = false)
        val forwarded = mutableListOf<Observation.Notification>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.pipeline.output().collect { forwarded += it } }
        h.source.emit(push()); advanceUntilIdle()
        h.source.emit(push("New order", channel = "dasher-notification-channel-new-order-v2")); advanceUntilIdle()
        assertEquals(1, forwarded.size)
        assertEquals(listOf("capture", "capture"), h.order)
        assertTrue(h.records.isEmpty())
        assertEquals(0L, h.stats.censusNotificationCount())
    }
    @Test fun `census failure does not terminate collection or alter later recognized forwarding`() = runTest {
        val h = Harness(sinkFailure = IllegalStateException("private payload"))
        val forwarded = mutableListOf<Observation.Notification>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.pipeline.output().collect { forwarded += it } }
        h.source.emit(push()); advanceUntilIdle()
        h.source.emit(push("New order", channel = "dasher-notification-channel-new-order-v2")); advanceUntilIdle()
        assertEquals(1L, h.stats.censusPublishFailureCount())
        assertEquals(1, forwarded.size)
        assertEquals("new_order", forwarded.single().target)
        assertEquals("capture-after-admission", forwarded.single().captureId)
    }

}
