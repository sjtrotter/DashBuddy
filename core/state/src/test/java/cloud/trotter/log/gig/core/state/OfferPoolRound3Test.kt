package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1119 S2, review round 3 (Sol r3 S9, S10 on `40f91ebf2`). Written against APIs that head already had, so each was
 * run on it and fails there (the PR report names the failing assertion).
 */
class OfferPoolRound3Test {

    private fun pooled(): PoolDriver = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000))

    private fun push(at: Long, text: String) = Observation.Notification(
        timestamp = at, captureId = null, ruleId = "doordash.notification.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.NotificationFields(intent = "info", rawText = text),
    )

    // S9: flow-less pushes inside the hold are not work frames; the store-bearing pickup still links.
    @Test fun `S9 - two flow-less pushes inside the hold do not reach the frame cap`() {
        val d = pooled().step(
            PF.pickup(10_000, store = null),
            push(11_000, "first push"),
            push(12_000, "second push"),
            PF.pickup(13_000),
        )
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
    }

    // S10: a held store reading "Unknown" is store-less for the replay too, so the pickup stays one task.
    @Test fun `S10 - an arrived hold whose store reads Unknown keeps one pickup with the held arrival`() {
        fun task(at: Long, sub: TaskSubFlow, flow: Flow, store: String?) = Observation.Screen(
            timestamp = at, captureId = null, ruleId = "doordash.screen.synthetic", metadata = ReplayMetadata.EMPTY,
            flow = flow, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = sub, storeName = store),
        )
        val d = pooled().step(
            task(10_000, TaskSubFlow.ARRIVED, Flow.TaskPickupArrived, "Unknown"),
            task(11_000, TaskSubFlow.NAVIGATION, Flow.TaskPickupNavigation, PF.PICKUP_STORE),
        )
        val job = d.region.activeJob!!
        assertEquals(listOf("hA"), job.acceptedOffers.map { it.offerHash })
        assertEquals("the held arrival stays on the active pickup", 10_000L, d.region.activeTask!!.arrivedAt)
        val pickups = (job.tasks + d.region.recentTasks).filter { it.jobId == job.jobId && it.phase == TaskPhase.PICKUP }
            .map { it.taskId }.distinct()
        assertEquals(listOf(d.region.activeTask!!.taskId), pickups)
    }
}
