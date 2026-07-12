package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #691 — the pure eligibility + share policy extracted out of EffectMap (FIX 5). */
class OfferPayFallbackTest {

    private fun drop(id: String, completedAt: Long?) = Task(
        taskId = id, jobId = "J", phase = TaskPhase.DROPOFF,
        customerNameHash = "c-$id", startedAt = 100L, completedAt = completedAt,
    )

    private fun job(offerPay: Double?, tasks: List<Task>) = Job(
        jobId = "J", offerStoreHint = emptyList(), parentOfferHash = null,
        acceptedOffers = listOf(AcceptedOfferEconomics(offerHash = "h", payAmount = offerPay, acceptedAt = 50L)),
        tasks = tasks, startedAt = 50L,
    )

    @Test
    fun `a receipt suppresses the estimate entirely`() {
        val j = job(12.95, listOf(drop("d1", 400L)))
        val r = OfferPayFallback.shareFor(j, emptyList(), "d1", suppressedByReceipt = true, requireFinalShape = false)
        assertNull(r.share)
        assertFalse("suppressed is not an unsplit-miss", r.eligibleButUnsplit)
    }

    @Test
    fun `final-shape gate blocks a mid-stack drop and stamps the last open drop`() {
        val j = job(12.95, listOf(drop("d1", null), drop("d2", null)))
        // d1 minting while d2 still owed → not final shape → no stamp.
        val mid = OfferPayFallback.shareFor(j, emptyList(), "d1", suppressedByReceipt = false, requireFinalShape = true)
        assertNull("mid-stack → no stamp", mid.share)
        assertFalse(mid.eligibleButUnsplit)

        // d2 is the last open owed drop once d1 completed.
        val j2 = job(12.95, listOf(drop("d1", 380L), drop("d2", null)))
        val last = OfferPayFallback.shareFor(j2, emptyList(), "d2", suppressedByReceipt = false, requireFinalShape = true)
        assertEquals(6.47, last.share!!, 1e-9)
    }

    @Test
    fun `close-out (no final-shape requirement) stamps every owed drop's equal share`() {
        val j = job(12.95, listOf(drop("d1", 400L), drop("d2", 410L)))
        val a = OfferPayFallback.shareFor(j, emptyList(), "d1", suppressedByReceipt = false, requireFinalShape = false)
        val b = OfferPayFallback.shareFor(j, emptyList(), "d2", suppressedByReceipt = false, requireFinalShape = false)
        assertEquals(6.48, a.share!!, 1e-9)
        assertEquals(6.47, b.share!!, 1e-9)
    }

    @Test
    fun `a pay-less offer is eligible-but-unsplit (the WARN signal)`() {
        val j = job(offerPay = null, tasks = listOf(drop("d1", 400L)))
        val r = OfferPayFallback.shareFor(j, emptyList(), "d1", suppressedByReceipt = false, requireFinalShape = false)
        assertNull(r.share)
        assertTrue("eligible but the split yielded nothing", r.eligibleButUnsplit)
    }

    @Test
    fun `a minting task outside the owed set is eligible-but-unsplit`() {
        val j = job(12.95, listOf(drop("d1", 400L)))
        val r = OfferPayFallback.shareFor(j, emptyList(), "ghost", suppressedByReceipt = false, requireFinalShape = false)
        assertNull(r.share)
        assertTrue(r.eligibleButUnsplit)
    }

    @Test
    fun `owedDropoffs unions job tasks with the job's recentTasks dropoffs, ANY marker state, deduped`() {
        // #752: job.tasks (the outstanding-placeholder mirror) no longer retains an unassigned drop,
        // so the quoted-order denominator must union it back in from the lifecycle record. The union
        // keeps ANY marker state (unassigned included — that order was still quoted), dedupes by
        // taskId (d1 appears in both sources), and ignores foreign-job tasks.
        val survivor = drop("d1", 400L)
        val unassigned = drop("d2", null).copy(unassignedAt = 1_000L)
        val foreign = drop("dX", 500L).copy(jobId = "OTHER")
        val j = job(12.95, tasks = listOf(survivor))
        val owed = OfferPayFallback.owedDropoffs(j, recentTasks = listOf(survivor, unassigned, foreign))
        assertEquals(setOf("d1", "d2"), owed.map { it.taskId }.toSet())
        // And the split reads that union: the survivor's share is total/2, not the full quote.
        val r = OfferPayFallback.shareFor(
            j, listOf(survivor, unassigned, foreign), "d1",
            suppressedByReceipt = false, requireFinalShape = false,
        )
        assertEquals(6.48, r.share!!, 1e-9)
    }
}
