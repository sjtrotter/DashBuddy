package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase

class WaitingAnchorTest {
    private fun drop(at: Long = 5_000) = Task("drop", "job", TaskPhase.DROPOFF, startedAt = 2_000, completedAt = at)
    private fun receipt(at: Long) = FlowCardSnapshot.PostTask(3_000, at, "job")
    private fun offer(outcome: AppEventType) = FlowCardSnapshot.Offer(2_000, 6_000, "offer", outcome = outcome)
    private fun anchor(tasks: List<Task> = emptyList(), cards: List<FlowCardSnapshot> = emptyList(),
        resume: Long? = null) = WaitingAnchor.resolve(1_000, tasks, CardStack(cards), resume)

    @Test fun `dash start anchors the wait`() { assertEquals(1_000L, anchor()) }
    @Test fun `a completed job moves the anchor`() {
        assertEquals(5_000L, anchor(cards = listOf(receipt(5_000))))
        assertEquals(6_000L, anchor(listOf(drop()), listOf(receipt(6_000))))
    }
    @Test fun `a declined offer does not move the anchor`() {
        assertEquals(1_000L, anchor(cards = listOf(offer(AppEventType.OFFER_DECLINED))))
    }
    @Test fun `a timed out offer does not move the anchor`() {
        assertEquals(1_000L, anchor(cards = listOf(offer(AppEventType.OFFER_TIMEOUT))))
    }
    @Test fun `an unassigned dropoff does not move the anchor`() {
        assertEquals(1_000L, anchor(listOf(drop().copy(unassignedAt = 4_000))))
    }
    @Test fun `completions before the dash start are ignored`() {
        assertEquals(1_000L, anchor(listOf(drop(500)), listOf(receipt(900))))
        assertEquals(1_000L, anchor(listOf(drop().copy(phase = TaskPhase.PICKUP))))
    }
    @Test fun `the region completion wins before the fold catches up`() {
        assertEquals(5_000L, anchor(listOf(drop()), listOf(receipt(3_000))))
    }
    @Test fun `no session gives no anchor`() {
        assertNull(WaitingAnchor.resolve(null, listOf(drop()), CardStack(listOf(receipt(5_000))), 6_000))
    }
    @Test fun `a resume time moves the anchor`() {
        assertEquals(7_000L, anchor(listOf(drop()), resume = 7_000))
        assertEquals(5_000L, anchor(listOf(drop()), resume = 500))
    }
}
