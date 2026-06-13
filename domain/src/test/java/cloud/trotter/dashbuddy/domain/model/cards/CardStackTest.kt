package cloud.trotter.dashbuddy.domain.model.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #458 — CardStack.of drops the frozen completed card that duplicates the active card. */
class CardStackTest {

    private fun delivery(taskId: String, ended: Long? = null) = FlowCardSnapshot.Delivery(
        phaseStartedAt = 0L, phaseEndedAt = ended, taskId = taskId, jobId = "job-1",
    )

    private fun postTask(jobId: String) = FlowCardSnapshot.PostTask(
        phaseStartedAt = 0L, jobId = jobId,
    )

    @Test
    fun `frozen completed card with the same id as active is suppressed - the at-door overlap`() {
        // The mapper froze delivery:task-A into completed on DELIVERY_ARRIVED
        // while the live builder still emits an active delivery:task-A.
        val frozen = delivery("task-A", ended = 100L)
        val active = delivery("task-A")
        assertEquals("frozen.id == active.id", frozen.id, active.id)

        val stack = CardStack.of(completed = listOf(frozen), active = active)

        assertTrue("the frozen twin is dropped", stack.completed.none { it.id == active.id })
        assertEquals("exactly the active card remains", active, stack.active)
        assertTrue(stack.completed.isEmpty())
    }

    @Test
    fun `distinct completed cards are kept`() {
        val older = delivery("task-A", ended = 100L)
        val active = delivery("task-B")
        val stack = CardStack.of(completed = listOf(older), active = active)
        assertEquals(listOf(older), stack.completed)
        assertEquals(active, stack.active)
    }

    @Test
    fun `null active keeps all completed`() {
        val cards = listOf(delivery("task-A", ended = 1L), postTask("job-1"))
        val stack = CardStack.of(completed = cards, active = null)
        assertEquals(cards, stack.completed)
    }

    @Test
    fun `the overlap resolves once active becomes a PostTask (different id)`() {
        // On DELIVERY_COMPLETED the live card becomes posttask:job-1 while the
        // frozen delivery:task-A stays — different ids, both shown (the single
        // frozen delivery + the live receipt), which is the correct end state.
        val frozen = delivery("task-A", ended = 100L)
        val active = postTask("job-1")
        val stack = CardStack.of(completed = listOf(frozen), active = active)
        assertEquals(listOf(frozen), stack.completed)
        assertEquals(active, stack.active)
    }
}
