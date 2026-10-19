package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
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
        val addon = logOffer("addon", 5_000)
        val groups = JobGrouper.group(CardStack(logStack().completed + addon), emptyList())
        assertEquals(listOf("offer", "addon"), groups.single().offers.map { it.offerHash })
    }
    @Test fun `accepted offer with no job stays a thin row`() {
        val offer = logOffer()
        val stack = CardStack(listOf(offer))
        assertTrue(JobGrouper.group(stack, emptyList()).isEmpty())
        val row = LogMapper.map(stack, LogData.Empty, emptyList(), null).single() as OfferRow
        assertFalse(row.dimmed)
    }
    @Test fun `unassigned event creates a group even without a task snapshot`() {
        val group = JobGrouper.group(CardStack(listOf(logOffer())), listOf(logUnassigned())).single()
        assertEquals("job", group.jobId)
        assertEquals(10_000L, group.unassignedAt)
        assertEquals(listOf(logOffer()), group.offers)
    }
}
