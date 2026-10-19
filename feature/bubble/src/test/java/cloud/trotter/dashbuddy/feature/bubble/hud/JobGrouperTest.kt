package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import org.junit.Assert.*
import org.junit.Test

class JobGrouperTest {
    @Test fun `accepted offer links to its job by offer hash before time`() {
        val offer = logOffer(at = 5_000)
        val groups = JobGrouper.group(CardStack(listOf(offer, logPickup("early", "early"),
            logPickup("linked", "linked", 20_000))), listOf(logLink("linked")))
        assertTrue(groups.single { it.jobId == "early" }.offers.isEmpty())
        assertEquals(listOf(offer), groups.single { it.jobId == "linked" }.offers)
    }
    @Test fun `an accept with no link stays unassigned`() {
        val offer = logOffer(at = 5_000)
        val stack = CardStack(listOf(offer, logPickup(), logDelivery(),
            logPickup("next", "next-pickup", 5_001)))
        val groups = JobGrouper.group(stack, listOf(logAccepted(at = 5_000), logLink(hashes = emptyList())))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.offers.isEmpty() })
        assertTrue(JobGrouper.group(CardStack(listOf(offer)), emptyList()).isEmpty())
    }
    @Test fun `the live job's accepted hashes link its offer before pickup confirmation`() {
        val offer = logOffer(at = 5_000)
        val pickup = logPickup(start = 4_997).copy(phaseEndedAt = null, confirmedAt = null)
        val stack = CardStack(listOf(logPost("previous"), offer), pickup)
        val groups = JobGrouper.group(stack, listOf(logAccepted(at = 5_000)),
            liveLinks = mapOf("job" to setOf("offer")))
        val live = groups.single { it.jobId == "job" }
        assertTrue(live.live)
        assertEquals(listOf(offer), live.offers)
        assertTrue(groups.single { it.jobId == "previous" }.offers.isEmpty())
    }
    @Test fun `an event link wins over the live link`() {
        val offer = logOffer()
        val stack = CardStack(listOf(offer, logPickup("linked")), logPickup("live", "live-pickup"))
        val events = listOf(logLink("linked"))
        val expected = JobGrouper.group(stack, events)
        assertEquals(listOf(offer), expected.single { it.jobId == "linked" }.offers)
        assertEquals(expected, JobGrouper.group(stack, events, mapOf("linked" to setOf("offer"))))
        assertEquals(expected, JobGrouper.group(stack, events, mapOf("live" to setOf("offer"))))
    }
    @Test fun `a delivery event links the accepted offer`() {
        val offer = logOffer()
        val event = AppEvent(AppEventType.DELIVERY_NAV_STARTED, 4_000, "dash",
            DeliveryPayload("job", "drop", phaseStartedAt = 4_000, jobOfferHashes = listOf("offer")))
        val group = JobGrouper.group(CardStack(listOf(offer, logDelivery())), listOf(event)).single()
        assertEquals(listOf(offer), group.offers)
    }
    @Test fun `unassigned event creates a group even without a task snapshot`() {
        val group = JobGrouper.group(CardStack(listOf(logOffer())), listOf(logAccepted(), logUnassigned())).single()
        assertEquals("job", group.jobId)
        assertEquals(10_000L, group.unassignedAt)
        assertEquals(listOf(logOffer()), group.offers)
    }
}
