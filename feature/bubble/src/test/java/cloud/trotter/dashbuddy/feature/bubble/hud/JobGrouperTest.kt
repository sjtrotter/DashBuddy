package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis
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
    @Test fun `add-on accepted mid job joins the open job`() {
        val addon = logOffer("addon", 5_000).copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)
        val groups = JobGrouper.group(CardStack(logStack().completed + addon), emptyList())
        assertEquals(listOf("offer", "addon"), groups.single().offers.map { it.offerHash })
    }
    @Test fun `a standalone accept joins the next job not the receipt still on screen`() {
        val offer = logOffer("standalone", 10_000)
        val stack = CardStack(listOf(logPickup("j1"), logPost("j1", at = 12_000),
            offer, logPickup("j2", "pickup2", 13_000), logPickup("j3", "pickup3", 20_000)))
        // J2 has no hash link yet; its first pickup starts after the accept.
        val events = listOf(logAccepted("standalone", 10_000), logLink("j1", emptyList()))
        val groups = JobGrouper.group(stack, events)
        assertTrue(groups.single { it.jobId == "j1" }.offers.isEmpty())
        assertEquals(listOf(offer), groups.single { it.jobId == "j2" }.offers)
        assertTrue(groups.single { it.jobId == "j3" }.offers.isEmpty())
        val onlyPrevious = stack.copy(completed = stack.completed.take(3))
        assertTrue(JobGrouper.group(onlyPrevious, events).single().offers.isEmpty())
    }
    @Test fun `a job started a few ms before the accept still takes it`() {
        val offer = logOffer("standalone", 10_000)
        val stack = CardStack(listOf(offer, logPickup("later", "pickup2", 20_000),
            logPickup("job", "pickup", 9_997)))
        val groups = JobGrouper.group(stack, listOf(logAccepted("standalone", 10_000)))
        assertEquals(listOf(offer), groups.single { it.jobId == "job" }.offers)
        assertTrue(groups.single { it.jobId == "later" }.offers.isEmpty())
    }
    @Test fun `an add-on accept joins the open job`() {
        val offer = logOffer("addon", 5_000).copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)
        val stack = CardStack(listOf(logPickup("j1"), logDelivery("j1"), offer,
            logPickup("j2", "pickup2", 13_000)))
        val events = listOf(logAccepted("addon", 5_000), logLink("j1", emptyList()))
        val groups = JobGrouper.group(stack, events)
        assertEquals(listOf(offer), groups.single { it.jobId == "j1" }.offers)
        assertTrue(groups.single { it.jobId == "j2" }.offers.isEmpty())
    }
    @Test fun `accepted offer with no job stays a thin row`() {
        val offer = logOffer()
        val stack = CardStack(listOf(offer))
        assertTrue(JobGrouper.group(stack, emptyList()).isEmpty())
        val row = LogMapper.map(stack, LogData.Empty, emptyList(), null).single() as OfferRow
        assertFalse(row.dimmed)
    }
    @Test fun `unassigned event creates a group even without a task snapshot`() {
        val group = JobGrouper.group(CardStack(listOf(logOffer())), listOf(logAccepted(), logUnassigned())).single()
        assertEquals("job", group.jobId)
        assertEquals(10_000L, group.unassignedAt)
        assertEquals(listOf(logOffer()), group.offers)
    }
    @Test fun `temporal fallback stays inside the offer's session`() {
        val addon = logOffer("addon", 5_000).copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)
        val stack = CardStack(listOf(logPickup("a", "pickup-a", 2_000),
            logDelivery("a", "drop-a", 4_000), logPickup("b", "pickup-b", 4_000),
            logDelivery("b", "drop-b", 6_000), addon))
        val events = listOf(logAccepted("addon", 5_000, "A"),
            logLink("a", emptyList(), 2_000).copy(sessionId = "A"),
            logLink("b", emptyList(), 4_000).copy(sessionId = "B"))
        val groups = JobGrouper.group(stack, events)
        assertEquals(listOf(addon), groups.single { it.jobId == "a" }.offers)
        assertTrue(groups.single { it.jobId == "b" }.offers.isEmpty())
    }
    @Test fun `two open jobs in one session leave the offer unassigned`() {
        val addon = logOffer("addon", 5_000).copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)
        val stack = CardStack(listOf(logPickup("a", "pickup-a", 2_000),
            logDelivery("a", "drop-a", 4_000), logPickup("b", "pickup-b", 4_000),
            logDelivery("b", "drop-b", 6_000), logPickup("later", "pickup-later", 20_000), addon))
        val events = listOf(logAccepted("addon", 5_000), logLink("a", emptyList()),
            logLink("b", emptyList()), logLink("later", emptyList(), 20_000))
        assertTrue(JobGrouper.group(stack, events).all { it.offers.isEmpty() })
        val row = LogMapper.map(stack, LogData(events = events), emptyList(), null)
            .filterIsInstance<OfferRow>().single()
        assertEquals(addon.id, row.key)
        assertEquals(AppEventType.OFFER_ACCEPTED, row.outcome)
    }
    @Test fun `an offer with no session event still joins the only open job of unknown session`() {
        val offer = logOffer("addon", 5_000).copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)
        val stack = CardStack(listOf(logPickup(), logDelivery(), offer))
        assertEquals(listOf(offer), JobGrouper.group(stack, emptyList()).single().offers)
    }
    @Test fun `an offer with no session event never joins a job of known session`() {
        val stack = CardStack(listOf(logPickup(), logDelivery(), logOffer("addon", 5_000).copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)))
        assertTrue(JobGrouper.group(stack, listOf(logLink(hashes = emptyList()))).single().offers.isEmpty())
    }
    @Test fun `standalone fallback picks the first next job in the session`() {
        val offer = logOffer("addon", 1_000)
        val a = logPickup("a", "pickup-a", 2_000)
        val b = logPickup("b", "pickup-b", 4_000)
        val accepted = logAccepted("addon", 1_000, "A")
        val aEvent = logLink("a", emptyList(), 2_000).copy(sessionId = "A")
        val bEvent = logLink("b", emptyList(), 4_000).copy(sessionId = "B")
        val stack = CardStack(listOf(offer, a, b))
        val groups = JobGrouper.group(stack, listOf(accepted, aEvent, bEvent))
        assertEquals(listOf(offer), groups.single { it.jobId == "a" }.offers)
        assertTrue(groups.single { it.jobId == "b" }.offers.isEmpty())
        val sameSession = JobGrouper.group(stack, listOf(accepted, aEvent, bEvent.copy(sessionId = "A")))
        assertEquals(listOf(offer), sameSession.single { it.jobId == "a" }.offers)
        assertTrue(sameSession.single { it.jobId == "b" }.offers.isEmpty())
    }

}
