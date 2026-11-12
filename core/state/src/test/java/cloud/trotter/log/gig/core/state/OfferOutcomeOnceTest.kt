package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.log.gig.domain.pipeline.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2 invariant: every offer instance gets exactly one outcome event. For each path, every
 * `OFFER_ACCEPTED`/`DECLINED`/`NOT_TAKEN`/`TIMEOUT` is collected by `offerInstanceId`: at most one per instance after
 * every step, exactly one per received instance after the closing shift end, and no `OFFER_TIMEOUT` ever.
 *
 * Non-vacuity: on master `77c089dac` no outcome payload carries an instance id, every leave or replace logs
 * `OFFER_TIMEOUT`, and nothing is logged at the shift end for an offer already resolved — so the zero-TIMEOUT
 * assertion fails on every leave path.
 */
class OfferOutcomeOnceTest {

    private fun run(vararg obs: Observation, endAt: Long = 200_000): PoolDriver {
        val d = PoolDriver()
        for (o in obs) {
            d.step(o)
            val perInstance = d.outcomes().groupingBy { (it.payload as OfferPayload).offerInstanceId }.eachCount()
            assertTrue("at most one outcome per instance after every step: $perInstance", perInstance.values.all { it <= 1 })
        }
        d.endShift(endAt)
        val received = d.events.filter { it.type == AppEventType.OFFER_RECEIVED }
            .map { (it.payload as OfferReceivedPayload).offerInstanceId }
        val outcomes = d.outcomes().groupingBy { (it.payload as OfferPayload).offerInstanceId }.eachCount()
        assertEquals("exactly one outcome per received instance", received.associateWith { 1 }, outcomes)
        assertTrue(d.outcomes().none { it.type == AppEventType.OFFER_TIMEOUT })
        return d
    }

    private fun types(d: PoolDriver) = d.outcomes().map { it.type }

    @Test fun `leave then link`() {
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), types(run(PF.card(1_000, "hA"), PF.idle(5_000), PF.pickup(10_000))))
    }

    @Test fun `leave then shift end`() {
        assertEquals(listOf(AppEventType.OFFER_NOT_TAKEN), types(run(PF.card(1_000, "hA"), PF.idle(5_000))))
    }

    @Test fun `replace then link`() {
        val d = run(PF.card(1_000, "hA"), PF.card(3_000, "hB", stores = listOf("Store B")), PF.idle(5_000), PF.pickup(10_000))
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED, AppEventType.OFFER_NOT_TAKEN), types(d))
    }

    @Test fun `replace then shift end`() {
        val d = run(PF.card(1_000, "hA"), PF.card(3_000, "hB", stores = listOf("Store B")), PF.idle(5_000))
        assertEquals(listOf(AppEventType.OFFER_NOT_TAKEN, AppEventType.OFFER_NOT_TAKEN), types(d))
    }

    @Test fun `expiry then link`() {
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED),
            types(run(PF.card(1_000, "hA"), PF.expiry(121_000, "hA", PF.instanceOf(1_000)), PF.pickup(122_700))))
    }

    @Test fun `expiry then shift end`() {
        assertEquals(listOf(AppEventType.OFFER_NOT_TAKEN),
            types(run(PF.card(1_000, "hA"), PF.expiry(121_000, "hA", PF.instanceOf(1_000)))))
    }

    @Test fun `committed decline`() {
        assertEquals(listOf(AppEventType.OFFER_DECLINED), types(run(PF.card(1_000, "hA"), PF.decline(2_000), PF.idle(5_000))))
    }

    @Test fun `latched accept through a leave, a replace and the shift end`() {
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED),
            types(run(PF.card(1_000, "hA"), PF.accept(2_000), PF.idle(5_000), PF.pickup(10_000))))
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED, AppEventType.OFFER_NOT_TAKEN),
            types(run(PF.card(1_000, "hA"), PF.accept(2_000), PF.card(3_000, "hB", stores = listOf("Store B")))))
        val end = Observation.UiInput(timestamp = 3_000, action = Observation.UiInput.ACTION_END_SHIFT,
            targetPlatform = PF.platform, sessionId = "S1")
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), types(run(PF.card(1_000, "hA"), PF.accept(2_000), end)))
    }

    @Test fun `tie then shift end`() {
        val d = run(PF.card(1_000, "hA"), PF.idle(5_000), PF.card(6_000, "hB"), PF.idle(9_000), PF.pickup(10_000))
        assertEquals(listOf(AppEventType.OFFER_NOT_TAKEN, AppEventType.OFFER_NOT_TAKEN), types(d))
        assertTrue(d.outcomePayloads().all { it.ambiguousJobIds.size == 1 })
    }

    @Test fun `an enrich-as-variant is one instance`() {
        val d = run(PF.card(1_000, "h1", key = "K"), PF.card(3_000, "h2", key = "K"), PF.idle(5_000), PF.pickup(10_000))
        assertEquals(listOf(AppEventType.OFFER_ACCEPTED), types(d))
        assertEquals(PF.instanceOf(1_000), d.outcomePayloads().single().offerInstanceId)
    }

    @Test fun `a same-hash re-presentation is two instances with two outcomes`() {
        val d = run(PF.card(1_000, "hA"), PF.idle(5_000), PF.card(20_000, "hA"), PF.idle(25_000))
        assertEquals(setOf(PF.instanceOf(1_000), PF.instanceOf(20_000)), d.outcomePayloads().map { it.offerInstanceId }.toSet())
    }
}
