package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #344 — entity IDs must be replay-deterministic. The stepper used to mint
 * session/job/task IDs with UUID.randomUUID(), so crash-recovery replay produced
 * different IDs than the live run: ID-keyed effect idempotency
 * ("start_session:$sessionId") never matched `effects_fired`, and restored state
 * diverged from already-persisted rows. IDs now derive from (kind, platform,
 * obs.timestamp, mintCounter).
 */
class DeterministicIdsTest {

    private fun idleObs(timestamp: Long) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun taskObs(
        flow: Flow,
        phase: TaskPhase,
        subFlow: TaskSubFlow,
        storeName: String? = null,
        customerAddressHash: String? = null,
        timestamp: Long,
    ) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = phase, subFlow = subFlow,
            storeName = storeName, customerAddressHash = customerAddressHash,
        ),
    )

    /**
     * Drives a fresh stepper through: go online (session mint) → pickup nav
     * (job + task mint) → dropoff nav (second task mint) — all at the SAME
     * observation timestamp, the hardest case for non-counter schemes.
     */
    private fun runScript(): PlatformRegion {
        val stepper = PlatformRegionStepper()
        val flowStepper = FlowRegionStepper()
        val policy = TransitionPolicy()
        var flow = FlowRegion()
        var region = PlatformRegion(Platform.DoorDash)

        fun apply(obs: Observation) {
            val nextFlow =
                if (obs is Observation.FlowObservation) flowStepper.step(flow, obs) else flow
            region = stepper.step(region, flow, nextFlow, obs, policy)
            flow = nextFlow
        }

        apply(idleObs(timestamp = 1_000L))
        apply(
            taskObs(
                Flow.TaskPickupNavigation, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION,
                storeName = "HEB", timestamp = 1_000L,
            )
        )
        apply(
            taskObs(
                Flow.TaskDropoffNavigation, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION,
                customerAddressHash = "cust-1", timestamp = 1_000L,
            )
        )
        return region
    }

    @Test
    fun `the same observation script mints identical ids on every run`() {
        val a = runScript()
        val b = runScript()

        assertNotNull(a.session?.sessionId)
        assertEquals(a.session?.sessionId, b.session?.sessionId)
        assertEquals(a.activeJob?.jobId, b.activeJob?.jobId)
        assertEquals(a.activeTask?.taskId, b.activeTask?.taskId)
        assertEquals(
            a.recentTasks.map { it.taskId },
            b.recentTasks.map { it.taskId },
        )
    }

    @Test
    fun `mints sharing one observation timestamp still get distinct ids`() {
        val region = runScript()

        val pickupTaskId = region.recentTasks.last().taskId
        val dropoffTaskId = region.activeTask?.taskId

        // Both tasks were minted at timestamp 1000 — the counter keeps them apart.
        assertNotEquals(pickupTaskId, dropoffTaskId)
        assertNotEquals(region.activeJob?.jobId, pickupTaskId)
        assertNotEquals(region.session?.sessionId, region.activeJob?.jobId)
    }

    @Test
    fun `mintCounter advances with every mint`() {
        val region = runScript()
        // session + job + pickup task + dropoff task = 4 mints
        assertEquals(4L, region.mintCounter)
    }
}
