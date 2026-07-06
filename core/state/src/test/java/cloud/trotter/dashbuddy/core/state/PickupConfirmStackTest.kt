package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #526 D5 (Bug10a) — a stacked pickup DISPLACED by the next pickup must emit PICKUP_CONFIRMED for
 * the displaced leg (today only the FINAL pickup is confirmed, at the first dropoff). The confirm
 * is keyed on the confirmed task (D5a) so a re-route can't double-confirm, and is emitted BEFORE
 * the next pickup's nav (D5b).
 */
class PickupConfirmStackTest {

    private val effectMap = EffectMap()

    private fun appState(flow: FlowRegion, region: PlatformRegion) = AppState(
        regions = Regions(flow = flow, platforms = mapOf(Platform.DoorDash to region), crossPlatform = CrossPlatformRegion()),
    )

    private fun pickup(taskId: String, store: String, arrivedAt: Long?, startedAt: Long) = Task(
        taskId = taskId, jobId = "J1", phase = TaskPhase.PICKUP, storeName = store,
        arrivedAt = arrivedAt, startedAt = startedAt,
    )

    private fun region(active: Task) = PlatformRegion(
        platform = Platform.DoorDash, mode = Mode.Online, session = Session("s1", startedAt = 100L), activeTask = active,
    )

    private fun screenObs(flow: Flow, t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "test.rule", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun logEvents(prev: AppState, next: AppState, obs: Observation) =
        effectMap.diff(prev, next, obs).filterIsInstance<AppEffect.LogEvent>()

    @Test
    fun `a pickup displaced by another pickup is CONFIRMED (Bug10a)`() {
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L)
        val b = pickup("T-B", "Mama Margies", arrivedAt = null, startedAt = 2_000L)
        val prev = appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a))
        val next = appState(FlowRegion(flow = Flow.TaskPickupNavigation), region(b))
        val obs = screenObs(Flow.TaskPickupNavigation, 2_000L)

        val events = logEvents(prev, next, obs)
        val confirmed = events.firstOrNull { it.event.type == AppEventType.PICKUP_CONFIRMED }
        assertNotNull("the displaced pickup must be confirmed (master: never)", confirmed)
        assertEquals("confirms the DISPLACED task, not the new one", "T-A", (confirmed!!.event.payload as PickupPayload).taskId)

        // NO DELIVERY_NAV_STARTED on a pickup→pickup edge.
        assertTrue(
            "no delivery nav on a pickup→pickup edge",
            events.none { it.event.type == AppEventType.DELIVERY_NAV_STARTED },
        )
    }

    @Test
    fun `D5b - CONFIRMED(prev) is emitted before NAV_STARTED(next)`() {
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L)
        val b = pickup("T-B", "Mama Margies", arrivedAt = null, startedAt = 2_000L)
        val prev = appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a))
        val next = appState(FlowRegion(flow = Flow.TaskPickupNavigation), region(b))
        val events = logEvents(prev, next, screenObs(Flow.TaskPickupNavigation, 2_000L))

        val confirmIdx = events.indexOfFirst { it.event.type == AppEventType.PICKUP_CONFIRMED }
        val navIdx = events.indexOfFirst { it.event.type == AppEventType.PICKUP_NAV_STARTED }
        assertTrue("CONFIRMED(prev) precedes NAV_STARTED(next)", confirmIdx in 0 until navIdx)
    }

    @Test
    fun `D5a - PICKUP_CONFIRMED is keyed on the confirmed task id (double-confirm dedup)`() {
        // Both a pickup→pickup displacement AND a pickup→dropoff confirm of the SAME task produce
        // the SAME effectKey, so the engine's effects_fired gate dedups a re-route double-confirm.
        val a = pickup("T-A", "Bill Miller BBQ", arrivedAt = 1_800L, startedAt = 1_000L)

        // pickup → pickup
        val ppEvents = logEvents(
            appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a)),
            appState(FlowRegion(flow = Flow.TaskPickupNavigation), region(pickup("T-B", "Mama Margies", null, 2_000L))),
            screenObs(Flow.TaskPickupNavigation, 2_000L),
        )
        val ppKey = ppEvents.first { it.event.type == AppEventType.PICKUP_CONFIRMED }.effectKey

        // pickup → dropoff (the SAME task A confirmed again on a later re-route)
        val pdEvents = logEvents(
            appState(FlowRegion(flow = Flow.TaskPickupArrived), region(a)),
            appState(
                FlowRegion(flow = Flow.TaskDropoffNavigation),
                region(Task(taskId = "T-D", jobId = "J1", phase = TaskPhase.DROPOFF, startedAt = 3_000L, customerNameHash = "cx")),
            ),
            screenObs(Flow.TaskDropoffNavigation, 3_000L),
        )
        val pdKey = pdEvents.first { it.event.type == AppEventType.PICKUP_CONFIRMED }.effectKey

        assertEquals("both confirm branches key on the task id", "log:PICKUP_CONFIRMED:T-A", ppKey)
        assertEquals("both confirm branches key on the task id", "log:PICKUP_CONFIRMED:T-A", pdKey)
    }
}
