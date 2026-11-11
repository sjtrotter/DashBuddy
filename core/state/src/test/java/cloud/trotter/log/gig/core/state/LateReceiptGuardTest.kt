package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.ReceiptScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1598 (OQ3) — a late receipt frame re-prices a closed job only if no offer has been presented since the receipt
 * was first read. The accept guard (`lastAcceptResolvedAt`) alone admits a frame after an offer that was shown and
 * then timed out, because a timeout resolves no accept.
 */
class LateReceiptGuardTest {

    private val receipt = RS.pay(base = 9.70, tip = 7.00)

    /** A proven single drop's collapsed receipt; the retire grace then commits on its timer, the receipt still up. */
    private fun closedOnCollapsed(vararg between: (ReceiptFold) -> ReceiptFold): ReceiptFold {
        var f = ReceiptFold(RS.live()).step(RS.postTask(RS.collapsed(16.70), 10_000L))
        between.forEach { f = it(f) }
        f.step(RS.timeout(30_000L, TimeoutType.GRACE_COMMIT))
        assertNull("the job closed", f.region.activeJob)
        assertNotNull("the receipt episode is still open", f.region.openReceiptEvidence)
        return f
    }

    @Test
    fun `a no offer since the first read - the late expanded frame re-prices once, marked proven`() {
        val f = closedOnCollapsed().step(RS.postTask(RS.expanded(receipt), 31_000L))
        val reprice = f.reprices().single()
        assertEquals("t1", reprice.taskId)
        assertEquals(16.70, reprice.dropRealizedPay, 1e-9)
        assertEquals(ReceiptScope.PROVEN_SINGLE, reprice.receiptScope)
        assertEquals("receipt:t1:10000", reprice.receiptEvidenceId)
    }

    @Test
    fun `b an offer presented and timed out since the first read - evidence only, no re-price`() {
        val f = closedOnCollapsed(
            { it.step(RS.offerScreen(11_000L, "hash-B")) },
            { it.step(RS.timeout(12_000L, TimeoutType.OFFER_EXPIRY)) },
            { it.step(RS.postTask(RS.collapsed(16.70), 13_000L)) },
        )
        assertNull("no accept resolved, so the accept guard alone would admit the frame", f.region.lastAcceptResolvedAt)
        assertEquals(1L, f.region.possibleWorkSeq)
        assertEquals(0L, f.region.openReceiptEvidence!!.possibleWorkSeqAtOpen)
        val before = f.evidence().size

        f.step(RS.postTask(RS.expanded(receipt), 31_000L))
        assertTrue("the late frame re-prices nothing", f.reprices().isEmpty())
        val revision = f.evidence().drop(before).single()
        assertEquals("the expansion is still saved as a revision of the same record", "receipt:t1:10000", revision.evidenceId)
        assertTrue(revision.isExpanded)
    }

    @Test
    fun `c the close-time ask with an expanded receipt still re-prices after an overlay`() {
        // The receipt expanded while the job was live (after an offer overlay), so the close decides from the
        // cached receipt — that ask is not a late frame and the guard does not apply.
        val f = ReceiptFold(RS.live())
            .step(RS.postTask(RS.collapsed(16.70), 10_000L))
            .step(RS.offerScreen(11_000L, "hash-B"))
            .step(RS.postTask(RS.expanded(receipt), 13_000L))
        assertEquals(1L, f.region.possibleWorkSeq)
        f.step(RS.timeout(30_000L, TimeoutType.GRACE_COMMIT))
        assertNull(f.region.activeJob)
        val reprice = f.reprices().single()
        assertEquals("t1", reprice.taskId)
        assertEquals(16.70, reprice.dropRealizedPay, 1e-9)
    }

    @Test
    fun `d an enrich of the presented offer does not count as new work`() {
        val f = ReceiptFold(RS.live())
            .step(RS.postTask(RS.collapsed(16.70), 10_000L))
            .step(RS.offerScreen(11_000L, "hash-B"))
            .step(RS.offerScreen(11_500L, "hash-B"))
        assertEquals("one presentation, one bump", 1L, f.region.possibleWorkSeq)
        f.step(RS.offerScreen(12_000L, "hash-C"))
        assertEquals("a different offer is a fresh presentation", 2L, f.region.possibleWorkSeq)
    }
}
