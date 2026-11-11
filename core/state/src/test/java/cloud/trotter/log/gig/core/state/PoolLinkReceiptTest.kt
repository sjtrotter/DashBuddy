package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1119 S2 × #1598: a job the pool linked to its one-drop card proves its receipt, exactly as a click accept does —
 * the link is the only thing that gives a missed accept's job an offer. Invented store names and amounts.
 *
 * Non-vacuity: on master `77c089dac` the card leaving to Idle logs `OFFER_TIMEOUT` and the pickup mints a job with no
 * offer, so case 1's receipt is `UNPROVEN` (gap `OFFER_COUNT`) and its drop carries no receipt pay, and case 2's drop
 * carries no `OFFER_PAY` estimate.
 */
class PoolLinkReceiptTest {

    private fun throughTheDrop(vararg before: cloud.trotter.log.gig.domain.pipeline.Observation): PoolDriver =
        PoolDriver().step(*before).step(
            PF.pickup(10_000),
            PF.pickup(11_000, flow = Flow.TaskPickupArrived),
            PF.dropoff(20_000),
            PF.dropoff(25_000, flow = Flow.TaskDropoffArrived),
        )

    private fun linked() = throughTheDrop(PF.card(1_000, "hA", pay = 9.0), PF.idle(5_000))

    private fun completion(d: PoolDriver) =
        d.events.single { it.type == AppEventType.DELIVERY_COMPLETED }.payload as DeliveryPayload

    private fun evidence(d: PoolDriver) =
        d.events.filter { it.type == AppEventType.RECEIPT_OBSERVED }.map { it.payload as ReceiptObservedPayload }.last()

    @Test fun `a linked one-drop job proves its receipt and the drop carries the receipt pay`() {
        val d = linked()
        assertEquals(listOf("hA"), d.region.activeJob!!.acceptedOffers.map { it.offerHash })
        d.step(RS.postTask(RS.expanded(RS.pay(base = 6.0, tip = 4.5)), 30_000), PF.idle(36_000))
        assertEquals(ReceiptScope.PROVEN_SINGLE, evidence(d).scope)
        val drop = completion(d)
        assertEquals(10.5, drop.dropRealizedPay!!, 1e-9)
        assertNull(drop.offerPayShare)
    }

    @Test fun `a linked drop with no receipt is priced by the card's pay estimate`() {
        val d = linked().step(PF.idle(30_000), PF.idle(45_000))
        val drop = completion(d)
        assertNull(drop.dropRealizedPay)
        assertEquals(9.0, drop.offerPayShare!!, 1e-9)
    }

    @Test fun `after a tie the receipt is unproven and nothing is estimated`() {
        val d = throughTheDrop(PF.card(1_000, "hA"), PF.idle(5_000), PF.card(6_000, "hB"), PF.idle(9_000))
        assertEquals(0, d.region.activeJob!!.acceptedOffers.size)
        d.step(RS.postTask(RS.expanded(RS.pay(base = 6.0, tip = 4.5)), 30_000), PF.idle(36_000))
        val read = evidence(d)
        assertEquals(ReceiptScope.UNPROVEN, read.scope)
        assertEquals(ReceiptProofGap.OFFER_COUNT, read.proofGap)
        val drop = completion(d)
        assertNull(drop.dropRealizedPay)
        assertNull(drop.offerPayShare)
    }
}
