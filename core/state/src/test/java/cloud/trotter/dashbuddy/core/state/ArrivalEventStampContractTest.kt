package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1310 — the fold identifies the stamping event from the producer's two timestamps. */
class ArrivalEventStampContractTest {
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(),
        CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )
    private val platform = Platform.DoorDash

    private fun navigating(): AppState {
        val drop = Task(
            taskId = "D1", jobId = "J1", phase = TaskPhase.DROPOFF,
            subPhase = TaskSubFlow.NAVIGATION, storeName = "Test Merchant",
            customerNameHash = "customer-1", startedAt = 90_000L,
        )
        val region = PlatformRegion(
            platform = platform, mode = Mode.Online,
            session = Session("dash-1", startedAt = 10_000L),
            activeJob = Job(
                jobId = "J1", offerStoreHint = listOf("Test Merchant"),
                parentOfferHash = null, tasks = listOf(drop), startedAt = 80_000L,
            ),
            activeTask = drop,
            lastActedFlow = Flow.TaskDropoffNavigation,
        )
        return AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.TaskDropoffNavigation, activePlatform = platform),
            platforms = mapOf(platform to region),
        ))
    }

    private fun screen(at: Long, flow: Flow, fields: ParsedFields.TaskFields) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = null, parsed = fields,
    )

    private fun arrival(at: Long) = screen(at, Flow.TaskDropoffArrived, ParsedFields.TaskFields(
        storeName = "Test Merchant", phase = TaskPhase.DROPOFF,
        subFlow = TaskSubFlow.ARRIVED, customerNameHash = "customer-1",
    ))

    @Test
    fun `1310 stamping arrival matches event time and resumed arrival keeps an earlier timestamp`() {
        val initial = navigating()
        assertNull(requireNotNull(initial.regions.platforms.getValue(platform).activeTask).arrivedAt)
        val stamped = machine.step(initial, arrival(100_000L))
        val stampingEvent = stamped.effects.filterIsInstance<AppEffect.LogEvent>()
            .single { it.event.type == AppEventType.DELIVERY_ARRIVED }.event
        val stampingPayload = stampingEvent.payload as DeliveryPayload
        assertEquals("D1", stampingPayload.taskId)
        assertEquals(100_000L, stampingEvent.occurredAt)
        assertEquals(stampingEvent.occurredAt, requireNotNull(stampingPayload.arrivedAt))
        assertEquals(stampingPayload.arrivedAt,
            requireNotNull(stamped.newState.regions.platforms.getValue(platform).activeTask).arrivedAt)

        // Leave for an un-arrived pickup, then resume the same dropoff from recentTasks.
        // The phase switch exercises TaskLifecycle's real resume lookup and EffectMap's arrival edge.
        val away = machine.step(stamped.newState, screen(110_000L, Flow.TaskPickupNavigation,
            ParsedFields.TaskFields(
                storeName = "Other Merchant", phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION,
            ),
        ))
        val awayRegion = away.newState.regions.platforms.getValue(platform)
        val otherTask = requireNotNull(awayRegion.activeTask)
        assertEquals(TaskPhase.PICKUP, otherTask.phase)
        assertNotEquals(stampingPayload.taskId, otherTask.taskId)
        assertNull(otherTask.arrivedAt)
        assertEquals(stampingPayload.arrivedAt,
            awayRegion.recentTasks.single { it.taskId == stampingPayload.taskId }.arrivedAt)

        val resumed = machine.step(away.newState, arrival(120_000L))
        val resumeEvent = resumed.effects.filterIsInstance<AppEffect.LogEvent>()
            .single { it.event.type == AppEventType.DELIVERY_ARRIVED }.event
        val resumePayload = resumeEvent.payload as DeliveryPayload
        assertEquals(stampingPayload.taskId, resumePayload.taskId)
        assertEquals(stampingPayload.jobId, resumePayload.jobId)
        assertEquals(stampingPayload.arrivedAt, resumePayload.arrivedAt)
        assertEquals(120_000L, resumeEvent.occurredAt)
        assertTrue(resumeEvent.occurredAt > requireNotNull(resumePayload.arrivedAt))
    }
}
