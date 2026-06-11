package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.accessibility.AccessibilityPipeline
import cloud.trotter.dashbuddy.core.pipeline.notification.NotificationPipeline
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.state.StateEvent
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * #361: PipelineV2.events is a HOT shared stream. The upstream chain runs side
 * effects (frame-dedup state, disk captures) inside its operators — when the
 * stream was cold, a second collector re-ran the whole chain, double-writing
 * captures and racing the dedup vars. Two collectors must now observe the SAME
 * upstream pass: one side-effect execution per event.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineV2ShareTest {

    private fun screenObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None, target = "main_map_idle",
    )

    @Test
    fun `two collectors share one upstream pass - side effects run once per event`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = MutableSharedFlow<Observation>(extraBufferCapacity = 8)
        var sideEffectRuns = 0

        val accessibilityPipeline: AccessibilityPipeline = mock()
        whenever(accessibilityPipeline.output())
            .thenReturn(source.onEach { sideEffectRuns++ })
        val notificationPipeline: NotificationPipeline = mock()
        whenever(notificationPipeline.output()).thenReturn(emptyFlow())

        val pipeline = PipelineV2(
            accessibilityPipeline = accessibilityPipeline,
            notificationPipeline = notificationPipeline,
            scope = backgroundScope,
        )

        val seenA = mutableListOf<StateEvent>()
        val seenB = mutableListOf<StateEvent>()
        backgroundScope.launch(dispatcher) { pipeline.events.collect { seenA += it } }
        backgroundScope.launch(dispatcher) { pipeline.events.collect { seenB += it } }
        runCurrent()

        source.emit(screenObs(1_000L))
        source.emit(screenObs(2_000L))
        runCurrent()

        // Both collectors observe both events ...
        assertEquals(2, seenA.size)
        assertEquals(2, seenB.size)
        // ... but the upstream side effect ran once per event, not per collector.
        assertEquals(2, sideEffectRuns)
    }
}
