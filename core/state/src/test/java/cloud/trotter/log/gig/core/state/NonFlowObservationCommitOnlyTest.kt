package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.PendingDestructive
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import cloud.trotter.log.gig.domain.state.mintWakeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #438 B3 characterization (item 9c): a platform-stamped non-flow observation
 * ([Observation.UiInput] / [Observation.Loopback]) stepping a region is **commit-only** —
 * it never drives a lifecycle EDGE (no task mint, no job start, no PostTask/completed
 * transition), never stamps [PlatformRegion.lastActedFlow] (it carries no flow), and only
 * ever commits an already-overdue lazy-expiry grace.
 *
 * This pins the invariant B3 depends on: once offers are stamped with platform identity (B2)
 * and moved onto the region (B3), an identity-less accept/decline UiInput or eval Loopback
 * that now DOES step the owning region must not fire a spurious edge. Authored to pass on
 * pre-B3 master AND unchanged after the move.
 */
class NonFlowObservationCommitOnlyTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    private val taskFlow = FlowRegion(flow = Flow.TaskPickupNavigation, activePlatform = Platform.DoorDash)

    private fun activeRegion(pending: PendingDestructive? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = Job(jobId = "job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        activeTask = Task(
            taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP,
            subPhase = TaskSubFlow.NAVIGATION, storeName = "Bill Miller BBQ", startedAt = 300L,
        ),
        lastActedFlow = Flow.TaskPickupNavigation,
        pendingDestructive = pending,
    )

    private fun uiInput(t: Long) = Observation.UiInput(
        timestamp = t, action = "accept_offer", targetPlatform = Platform.DoorDash, offerHash = "o1",
    )

    private fun loopback(t: Long) = Observation.Loopback(
        timestamp = t, effect = Observation.Loopback.EFFECT_OFFER_EVALUATED,
        targetPlatform = Platform.DoorDash,
        payload = ObservationPayload.EvaluationResult(action = "ACCEPT", offerHash = "o1"),
    )

    @Test
    fun `a UiInput with no overdue grace is inert - no edge, lastActedFlow unchanged`() {
        val region = activeRegion()
        val r = stepper.step(region, taskFlow, taskFlow, uiInput(5_000L), policy)
        assertEquals("same task, no re-mint", "task-1", r.activeTask?.taskId)
        assertEquals("same job, no lifecycle edge", "job-1", r.activeJob?.jobId)
        assertEquals("lastActedFlow unchanged (no flow carried)", Flow.TaskPickupNavigation, r.lastActedFlow)
        assertNotNull("session preserved", r.session)
    }

    @Test
    fun `a UiInput past a TASK_RETIRE deadline commits ONLY the lazy expiry`() {
        val pend = PendingDestructive(
            kind = DestructiveKind.TASK_RETIRE, since = 4_000L, deadline = 5_000L, armedFromFlow = Flow.Idle,
            wakeId = PlatformRegion(Platform.DoorDash, wakeSeq = 99L).mintWakeId().second,
        )
        // The task never arrived → retire completes it into recentTasks; the job stays open.
        val region = activeRegion(pend)
        val r = stepper.step(region, taskFlow, taskFlow, uiInput(6_000L), policy)
        assertNull("the overdue TASK_RETIRE committed — active task retired", r.activeTask)
        assertEquals("the retired task is in recentTasks", "task-1", r.recentTasks.lastOrNull()?.taskId)
        assertNull("grace cleared on commit", r.pendingDestructive)
        assertEquals("lastActedFlow unchanged by the non-flow obs", Flow.TaskPickupNavigation, r.lastActedFlow)
    }

    @Test
    fun `a Loopback with no overdue grace is inert - no edge, lastActedFlow unchanged`() {
        val region = activeRegion()
        val r = stepper.step(region, taskFlow, taskFlow, loopback(5_000L), policy)
        assertEquals("same task, no re-mint", "task-1", r.activeTask?.taskId)
        assertEquals("same job, no lifecycle edge", "job-1", r.activeJob?.jobId)
        assertEquals("lastActedFlow unchanged (no flow carried)", Flow.TaskPickupNavigation, r.lastActedFlow)
    }

    @Test
    fun `a Loopback past a TASK_RETIRE deadline commits ONLY the lazy expiry`() {
        val pend = PendingDestructive(
            kind = DestructiveKind.TASK_RETIRE, since = 4_000L, deadline = 5_000L, armedFromFlow = Flow.Idle,
            wakeId = PlatformRegion(Platform.DoorDash, wakeSeq = 99L).mintWakeId().second,
        )
        val region = activeRegion(pend)
        val r = stepper.step(region, taskFlow, taskFlow, loopback(6_000L), policy)
        assertNull("the overdue TASK_RETIRE committed — active task retired", r.activeTask)
        assertNull("grace cleared on commit", r.pendingDestructive)
        assertEquals("lastActedFlow unchanged by the non-flow obs", Flow.TaskPickupNavigation, r.lastActedFlow)
    }
}
