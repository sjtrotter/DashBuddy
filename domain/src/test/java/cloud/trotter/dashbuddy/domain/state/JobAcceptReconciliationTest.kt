package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #810 B1 — the pure job-close accept-reconciliation tripwire ([detectAcceptMismatch]).
 *
 * The true/false shapes below are the design's acceptance cases: the seq-114 invisible-unassign shape
 * FIRES; a genuine finished stack and a properly-#736'd unassign stay SILENT.
 */
class JobAcceptReconciliationTest {

    private fun offer(hash: String) = AcceptedOfferEconomics(offerHash = hash, payAmount = 10.0, acceptedAt = 50L)

    private fun job(offers: List<String>, tasks: List<Task>) = Job(
        jobId = "J",
        offerStoreHint = emptyList(),
        parentOfferHash = null,
        acceptedOffers = offers.map { offer(it) },
        tasks = tasks,
        startedAt = 50L,
    )

    /** A delivered dropoff: arrived + completed, not unassigned. */
    private fun deliveredDrop(id: String, customer: String) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF,
        customerNameHash = customer, startedAt = 100L, arrivedAt = 200L, completedAt = 300L,
    )

    /** A never-activated TBD dropoff placeholder: customer-less, undelivered. */
    private fun tbdPlaceholder(id: String) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF, startedAt = 100L,
    )

    /** An unassign-marked (abandoned) task. */
    private fun unassignedTask(id: String, phase: TaskPhase, customer: String?) = Task(
        taskId = id, jobId = "J", phase = phase, customerNameHash = customer,
        startedAt = 100L, unassignedAt = 250L,
    )

    // ---------------------------------------------------------------------------------------------
    // FIRES
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the seq-114 shape fires - 2 accepts, 1 delivered, 1 leftover TBD`() {
        // Job.tasks holds the leftover TBD placeholder (never activated → stays in the lineage mirror);
        // recentTasks holds the one delivered drop.
        val job = job(listOf("offerA", "offerB"), tasks = listOf(tbdPlaceholder("d-tbd")))
        val recent = listOf(deliveredDrop("d-delivered", "cust1"))

        val m = detectAcceptMismatch(job, recent)
        assertNotNull("2 accepts > 1 accounted → tripwire fires", m)
        assertEquals("J", m!!.jobId)
        assertEquals(2, m.acceptedCount)
        assertEquals("only the single delivered drop is accounted", 1, m.accountedCount)
        assertEquals(listOf("offerA", "offerB"), m.acceptedOfferHashes)
        assertEquals(listOf("cust1"), m.deliveredCustomerHashes)
        assertEquals(1, m.leftoverTbdPlaceholders)
        assertEquals(0, m.unassignedCount)
    }

    @Test
    fun `three accepts with only two accounted still fires`() {
        val job = job(listOf("a", "b", "c"), tasks = listOf(tbdPlaceholder("d-tbd")))
        val recent = listOf(deliveredDrop("d1", "c1"), deliveredDrop("d2", "c2"))
        val m = detectAcceptMismatch(job, recent)
        assertNotNull(m)
        assertEquals(3, m!!.acceptedCount)
        assertEquals(2, m.accountedCount)
    }

    // ---------------------------------------------------------------------------------------------
    // SILENT
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a genuine finished 2-drop stack is silent`() {
        // 2 accepts, both drops delivered (a real batch/add-on that completed) → accounted == accepts.
        val job = job(listOf("a", "b"), tasks = emptyList())
        val recent = listOf(deliveredDrop("d1", "c1"), deliveredDrop("d2", "c2"))
        assertNull("2 accounted == 2 accepts → no signal", detectAcceptMismatch(job, recent))
    }

    @Test
    fun `a single-accept drop-less close is silent (the early-offline class, nAccepts less-than 2)`() {
        // 1 accept, 0 delivered, a leftover TBD — the coarse/early-offline class, NOT the signal.
        val job = job(listOf("a"), tasks = listOf(tbdPlaceholder("d-tbd")))
        assertNull("single accept never trips the tripwire", detectAcceptMismatch(job, emptyList()))
    }

    @Test
    fun `a same-customer 1-offer-2-order job with a leftover TBD is silent (nAccepts less-than 2)`() {
        // One offer covering two orders (a same-customer multi-order), one delivered, one leftover.
        // nAccepts == 1 → below the guard, never fires (this is the #749 class, handled elsewhere).
        val job = job(listOf("a"), tasks = listOf(tbdPlaceholder("d-tbd")))
        val recent = listOf(deliveredDrop("d1", "c1"))
        assertNull(detectAcceptMismatch(job, recent))
    }

    @Test
    fun `a properly-736'd unassign counts as accounted and stays silent`() {
        // 2 accepts: one delivered, one explicitly unassigned via help (#736). accounted = 1 + 1 = 2.
        val job = job(listOf("a", "b"), tasks = emptyList())
        val recent = listOf(
            deliveredDrop("d1", "c1"),
            unassignedTask("d2", TaskPhase.DROPOFF, "c2"),
        )
        val m = detectAcceptMismatch(job, recent)
        assertNull("the unassign is accounted → 2 == 2, no signal", m)
    }

    @Test
    fun `an unassigned order's pickup and dropoff legs count as one accounted order`() {
        // 2 accepts, 1 delivered, 1 abandoned order whose pickup AND dropoff legs are both marked with
        // the SAME customer hash — must dedupe to one accounted order (else accounted would over-count).
        val job = job(listOf("a", "b"), tasks = emptyList())
        val recent = listOf(
            deliveredDrop("d1", "c1"),
            unassignedTask("p2", TaskPhase.PICKUP, "c2"),
            unassignedTask("d2", TaskPhase.DROPOFF, "c2"),
        )
        val m = detectAcceptMismatch(job, recent)
        // accounted = 1 delivered + 1 distinct unassigned customer = 2 == 2 accepts → silent.
        assertNull(m)
    }

    @Test
    fun `a delivered-but-never-arrived drop does not count as accounted`() {
        // completedAt set but arrivedAt null → not a fully delivered drop per the spec; 2 accepts, 0
        // accounted → fires (the drop is NOT counted).
        val job = job(listOf("a", "b"), tasks = emptyList())
        val notArrived = Task(
            taskId = "d1", jobId = "J", phase = TaskPhase.DROPOFF,
            customerNameHash = "c1", startedAt = 100L, arrivedAt = null, completedAt = 300L,
        )
        val m = detectAcceptMismatch(job, listOf(notArrived))
        assertNotNull("an unarrived completion is not accounted", m)
        assertEquals(0, m!!.accountedCount)
    }

    @Test
    fun `tasks from a different job are ignored`() {
        val job = job(listOf("a", "b"), tasks = emptyList())
        val foreign = deliveredDrop("d1", "c1").copy(jobId = "OTHER")
        val ownDelivered = deliveredDrop("d2", "c2")
        // Only the own-job delivered drop counts → 2 accepts, 1 accounted → fires.
        val m = detectAcceptMismatch(job, listOf(foreign, ownDelivered))
        assertNotNull(m)
        assertEquals(1, m!!.accountedCount)
        assertEquals(listOf("c2"), m.deliveredCustomerHashes)
    }

    @Test
    fun `union dedupes a task present in both job_tasks and recentTasks`() {
        val delivered = deliveredDrop("d1", "c1")
        // Same delivered task appears in BOTH the job mirror and recentTasks — must count once.
        val job = job(listOf("a", "b"), tasks = listOf(delivered, tbdPlaceholder("d-tbd")))
        val m = detectAcceptMismatch(job, listOf(delivered))
        assertNotNull(m)
        assertEquals("the delivered drop is counted once, not twice", 1, m!!.accountedCount)
        assertEquals(1, m.leftoverTbdPlaceholders)
    }

    @Test
    fun `the fresh recentTasks copy wins over a stale job_tasks mirror copy`() {
        // The Job.tasks mirror re-runs after stepCore, so at a close its copy of the just-finished drop
        // is STALE (completedAt/arrivedAt null); recentTasks holds the fresh delivered copy. The stale
        // mirror copy must NOT mask the finished one (else accountedCount would under-count → the drop
        // would look un-delivered). Same taskId in both pools.
        val staleMirrorCopy = Task(taskId = "d1", jobId = "J", phase = TaskPhase.DROPOFF, startedAt = 100L)
        val freshDelivered = deliveredDrop("d1", "c1")
        val job = job(listOf("a", "b"), tasks = listOf(staleMirrorCopy, tbdPlaceholder("d-tbd")))
        val m = detectAcceptMismatch(job, listOf(freshDelivered))
        assertNotNull(m)
        assertEquals("the finished recentTasks copy is what counts", 1, m!!.accountedCount)
        assertEquals(listOf("c1"), m.deliveredCustomerHashes)
        assertEquals(1, m.leftoverTbdPlaceholders)
    }
}
