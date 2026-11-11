package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2, review round 2 (Sol r2 S2, S7, S8 on `f7814c31e`). Written against APIs that head already had, so each
 * was run on it and fails there (the PR report names the failing assertion).
 */
class OfferPoolRound2Test {

    private fun pooled(): PoolDriver = PoolDriver().step(PF.card(1_000, "hA"), PF.idle(5_000))

    private fun task(at: Long, phase: TaskPhase, sub: TaskSubFlow, flow: Flow, store: String?, items: Int? = null,
        customer: String? = null) = Observation.Screen(
        timestamp = at, captureId = null, ruleId = "doordash.screen.synthetic", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = phase, subFlow = sub, storeName = store, itemsRemaining = items,
            customerNameHash = customer, customerAddressHash = customer?.let { "addr-$it" }),
    )

    // S2: a held ARRIVED pickup released by a store-bearing NAVIGATION frame stays ONE task with its evidence.
    @Test fun `S2 - arrived then navigation keeps one pickup task with the held arrival`() {
        val d = pooled().step(
            task(10_000, TaskPhase.PICKUP, TaskSubFlow.ARRIVED, Flow.TaskPickupArrived, store = null, items = 5),
            task(11_000, TaskPhase.PICKUP, TaskSubFlow.NAVIGATION, Flow.TaskPickupNavigation, store = PF.PICKUP_STORE),
        )
        val job = d.region.activeJob!!
        assertEquals(listOf("hA"), job.acceptedOffers.map { it.offerHash })
        val active = d.region.activeTask!!
        assertEquals("the held arrival stays on the active pickup", 10_000L, active.arrivedAt)
        assertEquals(5, active.itemsRemaining)
        val pickups = (job.tasks + d.region.recentTasks).filter { it.jobId == job.jobId && it.phase == TaskPhase.PICKUP }
            .map { it.taskId }.distinct()
        assertEquals("one job, one pickup task", listOf(active.taskId), pickups)
    }

    // S7: an Offline frame interrupts the hold; the held frame mints bare, and the later pickup links nothing.
    @Test fun `S7 - an Offline non-task frame ends the hold in a bare job, so a later pickup does not link`() {
        val d = pooled().step(
            PF.pickup(10_000, store = null),
            PF.idle(11_000).copy(modeHint = Mode.Offline),
            PF.pickup(12_000),
        )
        assertTrue("no link after an interrupted hold", d.outcomes().none { it.type == AppEventType.OFFER_ACCEPTED })
        assertEquals(10_000L, d.region.activeJob!!.startedAt)
        assertTrue(d.region.activeJob!!.acceptedOffers.isEmpty())
    }

    // S8: a hold that starts on a store-less dropoff and links on a store-bearing dropoff announces the leg.
    @Test fun `S8 - a dropoff released from the hold emits DELIVERY_NAV_STARTED at its preserved start`() {
        val d = pooled().step(
            task(10_000, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, Flow.TaskDropoffNavigation, store = null, customer = "c1"),
            task(11_000, TaskPhase.DROPOFF, TaskSubFlow.NAVIGATION, Flow.TaskDropoffNavigation, store = PF.PICKUP_STORE,
                customer = "c1"),
        )
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        val nav = d.events.filter { it.type == AppEventType.DELIVERY_NAV_STARTED }.map { it.payload as DeliveryPayload }
        assertEquals(1, nav.size)
        assertEquals(10_000L, nav.single().phaseStartedAt)
    }
}
