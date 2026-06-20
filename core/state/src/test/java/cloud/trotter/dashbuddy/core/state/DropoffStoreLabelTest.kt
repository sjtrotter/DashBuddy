package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #526 — a dropoff labels its store from its OWN frame (the dropoff card carries it as
 * `Target (02426)` / `Maple Street Biscuit - Alamo Ranch`), and must NOT inherit the prior
 * pickup's store across the phase change. The 06-19 Target+Maple stack mislabelled the Target
 * order's drop "Maple Street" precisely because the cross-phase mint inherited the active pickup's
 * store. Single deliveries (where pickup and dropoff are the same store) are unaffected — the drop
 * just gets the store from its own frame instead of by inheritance.
 */
class DropoffStoreLabelTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    private fun region(activeTask: Task?) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        activeTask = activeTask,
    )

    private fun pickup(store: String) = Task(
        taskId = "pick-1", jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = store, arrivedAt = 800L, startedAt = 300L,
    )

    private fun dropoffObs(timestamp: Long, store: String? = null, customer: String? = "cust-1") =
        Observation.Screen(
            timestamp = timestamp, captureId = null, ruleId = "doordash.screen.dropoff_pre_arrival",
            metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(
                phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
                storeName = store, customerNameHash = customer, customerAddressHash = "addr-1",
            ),
        )

    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, obs: Observation): Pair<PlatformRegion, FlowRegion> {
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy) to nextFlow
    }

    @Test
    fun `a dropoff takes the store parsed from its own frame`() {
        val (r, _) = step(
            region(pickup("Maple Street Biscuit Company")),
            FlowRegion(flow = Flow.TaskPickupArrived),
            dropoffObs(1_000L, store = "Target (02426)", customer = "cust-target"),
        )
        assertEquals(TaskPhase.DROPOFF, r.activeTask?.phase)
        assertEquals("the drop is labelled by its own store, not the active pickup's", "Target (02426)", r.activeTask?.storeName)
    }

    @Test
    fun `a dropoff with no store frame does NOT inherit the pickup store (decontamination, #526)`() {
        // This is the 06-19 mislabel: pickup is Maple, the drop frame carries no store → it must
        // NOT become "Maple Street Biscuit Company"; it stays null until its own store frame arrives.
        val (r, _) = step(
            region(pickup("Maple Street Biscuit Company")),
            FlowRegion(flow = Flow.TaskPickupArrived),
            dropoffObs(1_000L, store = null, customer = "cust-target"),
        )
        assertEquals(TaskPhase.DROPOFF, r.activeTask?.phase)
        assertNull("a cross-phase mint must not inherit the pickup's store", r.activeTask?.storeName)
    }

    @Test
    fun `the dropoff store sticks once parsed, across later store-less frames`() {
        val (r1, f1) = step(
            region(pickup("Target")),
            FlowRegion(flow = Flow.TaskPickupArrived),
            dropoffObs(1_000L, store = "Target (02426)", customer = "cust-1"),
        )
        // A later same-phase dropoff frame without the store node keeps the resolved store.
        val (r2, _) = step(r1, f1, dropoffObs(2_000L, store = null, customer = "cust-1"))
        assertEquals("Target (02426)", r2.activeTask?.storeName)
    }
}
