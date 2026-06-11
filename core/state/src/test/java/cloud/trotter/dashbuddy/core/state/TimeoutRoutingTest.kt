package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #342 — [Observation.Timeout] must step the region it was armed for. Timeouts carry
 * no ruleId, so without an explicit [Observation.Timeout.targetPlatform] they derive
 * [Platform.Unknown] and the owning region never sees the fire — which left the
 * SESSION_PAUSED_SAFETY timer dead at the region level.
 */
class TimeoutRoutingTest {

    private val machine = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = TransitionPolicy(),
        effectMap = EffectMap(),
    )

    private fun pausedRegion() = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Paused,
        session = Session("sess-1", startedAt = 1_000L),
    )

    private fun state(region: PlatformRegion) = AppState(
        regions = Regions(platforms = mapOf(Platform.DoorDash to region)),
        timestamp = 1_000L,
    )

    @Test
    fun `pause-safety timeout routed to its platform expires the pause`() {
        val obs = Observation.Timeout(
            timestamp = 5_000L,
            type = TimeoutType.SESSION_PAUSED_SAFETY,
            targetPlatform = Platform.DoorDash,
        )

        val next = machine.step(state(pausedRegion()), obs).newState
        val region = next.regions.platforms.getValue(Platform.DoorDash)

        // Paused → Offline with a provisional (graced) session end armed.
        assertEquals(Mode.Offline, region.mode)
        assertEquals(DestructiveKind.SESSION_END, region.pendingDestructive?.kind)
    }

    @Test
    fun `timeout without a target platform leaves other regions untouched`() {
        val obs = Observation.Timeout(
            timestamp = 5_000L,
            type = TimeoutType.SESSION_PAUSED_SAFETY,
        )

        val next = machine.step(state(pausedRegion()), obs).newState

        // Routed to Platform.Unknown — the DoorDash region must not change.
        assertEquals(Mode.Paused, next.regions.platforms.getValue(Platform.DoorDash).mode)
    }

    @Test
    fun `routed timeout commits an expired task-retire deadline (lazy expiry)`() {
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("sess-1", startedAt = 100L),
            activeJob = Job("j1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
            activeTask = Task(
                taskId = "t1", jobId = "j1", phase = TaskPhase.PICKUP,
                storeName = "HEB", startedAt = 300L,
            ),
            pendingDestructive = PendingDestructive(
                DestructiveKind.TASK_RETIRE, since = 800L, deadline = 1_000L,
            ),
        )
        // A non-flow observation past the deadline — only reaches the region when routed.
        val obs = Observation.Timeout(
            timestamp = 2_000L,
            type = TimeoutType.SETTLE_UI,
            targetPlatform = Platform.DoorDash,
        )

        val next = machine.step(state(region), obs).newState

        assertNull(next.regions.platforms.getValue(Platform.DoorDash).activeTask)
    }
}
