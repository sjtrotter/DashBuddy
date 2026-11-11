package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1598 — every receipt read is saved as evidence: one record per (receipt episode, subject), revised in place. */
class ReceiptEvidenceTest {

    private val receipt = RS.pay(base = 9.70, tip = 7.00)

    private fun fold() = ReceiptFold(RS.live())

    @Test
    fun `a collapsed read then its expansion is one record with two revisions`() {
        val f = fold().step(RS.postTask(RS.collapsed(16.70), 10_000L)).step(RS.postTask(RS.expanded(receipt), 12_000L))
        val ev = f.evidence()
        assertEquals(2, ev.size)
        assertEquals(setOf("receipt:t1:10000"), ev.map { it.evidenceId }.toSet())
        assertEquals(listOf(1, 2), ev.map { it.revision })
        assertEquals(
            listOf("log:RECEIPT_OBSERVED:receipt:t1:10000:r1", "log:RECEIPT_OBSERVED:receipt:t1:10000:r2"),
            f.evidenceKeys(),
        )
        assertEquals(listOf(false, true), ev.map { it.isExpanded })
        assertEquals(listOf(10_000L, 10_000L), ev.map { it.firstSeenAt })
        assertEquals(listOf(10_000L, 12_000L), ev.map { it.observedAt })
    }

    @Test
    fun `an unchanged re-render records nothing, nor does a collapsed re-read of the expanded receipt`() {
        val f = fold()
            .step(RS.postTask(RS.expanded(receipt), 10_000L))
            .step(RS.postTask(RS.expanded(receipt), 10_500L))
            .step(RS.postTask(RS.collapsed(receipt.total), 11_000L))
        assertEquals(1, f.evidence().size)
    }

    @Test
    fun `an offer overlay over the receipt keeps the episode, so the re-entry revises the same record`() {
        val f = fold()
            .step(RS.postTask(RS.collapsed(16.70), 10_000L))
            .step(RS.offerScreen(11_000L, "hash-B"))
            .step(RS.postTask(RS.expanded(receipt), 13_000L))
        val ev = f.evidence()
        assertEquals(listOf(1, 2), ev.map { it.revision })
        assertEquals(1, ev.map { it.evidenceId }.toSet().size)
    }

    @Test
    fun `a frame that leaves the receipt ends the episode, so the next read of the same subject is a new record`() {
        val f = fold()
            .step(RS.postTask(RS.collapsed(16.70), 10_000L))
            .step(RS.screen(Flow.TaskDropoffNavigation, 11_000L))
        assertNull("the episode ended", f.region.openReceiptEvidence)
        f.step(RS.postTask(RS.collapsed(16.70), 12_000L))
        val ev = f.evidence()
        assertEquals(2, ev.size)
        assertEquals(listOf("t1", "t1"), ev.map { it.subjectTaskId })
        assertNotEquals(ev[0].evidenceId, ev[1].evidenceId)
        assertEquals(listOf(1, 1), ev.map { it.revision })
    }

    @Test
    fun `two subjects with equal totals are two records`() {
        val first = fold().step(RS.postTask(RS.collapsed(10.0), 10_000L))
        // The next drop of the job is now the active one, on the same receipt flow.
        val moved = first.region.copy(activeTask = RS.drop("t2"))
        val second = ReceiptFold(moved, Flow.PostTask).step(RS.postTask(RS.collapsed(10.0), 11_000L))
        assertEquals("receipt:t1:10000", first.evidence().single().evidenceId)
        assertEquals("receipt:t2:11000", second.evidence().single().evidenceId)
    }

    @Test
    fun `a zero pseudo-receipt, a session-less region and an unowned receipt record nothing`() {
        assertTrue(fold().step(RS.postTask(RS.collapsed(0.0), 10_000L)).evidence().isEmpty())
        assertTrue(ReceiptFold(RS.live().copy(session = null)).step(RS.postTask(RS.collapsed(16.70), 10_000L)).evidence().isEmpty())
        // No live job and no job closed on this receipt: the session owns no receipt job (#1103).
        val unowned = RS.live().copy(activeJob = null, activeTask = null, recentTasks = listOf(RS.drop("t1", completedAt = 4_000L)))
        assertTrue(ReceiptFold(unowned).step(RS.postTask(RS.collapsed(16.70), 10_000L)).evidence().isEmpty())
    }

    @Test
    fun `replaying the same observations from the same snapshot gives the same ids and keys`() {
        fun run() = fold()
            .step(RS.postTask(RS.collapsed(16.70), 10_000L))
            .step(RS.offerScreen(11_000L, "hash-B"))
            .step(RS.postTask(RS.expanded(receipt), 13_000L))
        val a = run()
        val b = run()
        assertEquals(a.evidence(), b.evidence())
        assertEquals(a.evidenceKeys(), b.evidenceKeys())
    }

    @Test
    fun `the payload carries the scope, gap, money, first sighting and platform`() {
        val proven = fold().step(RS.postTask(RS.expanded(receipt), 10_000L)).evidence().single()
        assertEquals(ReceiptScope.PROVEN_SINGLE, proven.scope)
        assertNull(proven.proofGap)
        assertEquals(16.70, proven.totalPay, 1e-9)
        assertEquals(9.70, proven.parsedPay!!.totalBasePay, 1e-9)
        assertEquals(7.00, proven.parsedPay!!.totalTip, 1e-9)
        assertEquals(10_000L, proven.firstSeenAt)
        assertEquals(Platform.DoorDash.wire, proven.platform)
        assertEquals("J1", proven.jobId)
        assertEquals("t1", proven.subjectTaskId)
        assertEquals("cap-10000", proven.sourceCaptureId)

        val twoOffers = ReceiptFold(RS.live(offers = listOf(RS.offer("o1"), RS.offer("o2"))))
            .step(RS.postTask(RS.collapsed(16.70), 10_000L)).evidence().single()
        assertEquals(ReceiptScope.UNPROVEN, twoOffers.scope)
        assertEquals(ReceiptProofGap.OFFER_COUNT, twoOffers.proofGap)
        assertNull(twoOffers.parsedPay)
        assertEquals(false, twoOffers.isExpanded)
    }

    @Test
    fun `the evidence event rides the region's session`() {
        val f = fold().step(RS.postTask(RS.collapsed(16.70), 10_000L))
        val event = f.events.single { it.type == AppEventType.RECEIPT_OBSERVED }
        assertEquals("S1", event.sessionId)
        assertEquals(10_000L, event.occurredAt)
    }
}
