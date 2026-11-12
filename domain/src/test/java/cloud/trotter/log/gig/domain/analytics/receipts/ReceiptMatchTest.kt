package cloud.trotter.log.gig.domain.analytics.receipts

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.DropPayApportioner
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1599: [ReceiptMatch], the one owner of what a receipt match may touch, its suggestion, split and preview. */
class ReceiptMatchTest {

    private fun row(
        seq: Long,
        jobId: String = "J1",
        pay: Double? = null,
        basis: String = PayBasis.NONE,
        hashes: List<String> = listOf("hY"),
        adjustedAt: Long? = null,
        assigned: Boolean = false,
    ) = DeliveryRecord(
        eventSequenceId = seq, sessionId = "S", platform = Platform.DoorDash, jobId = jobId, taskId = "t$seq",
        storeName = "Store A", completedAt = seq * 100, realizedPay = pay, payBasis = basis, netProfit = null,
        realizedMiles = 2.0, realizedMinutes = 10.0, tip = null, basePay = null, sessionAssigned = assigned,
        driverAdjustedAt = adjustedAt, jobOfferHashes = hashes,
    )

    private fun receipt(subjectSeq: Long, jobId: String = "J1", cents: Long = 2050) = SavedReceipt(
        evidenceId = "receipt:t$subjectSeq:1", jobId = jobId, subjectTaskId = "t$subjectSeq", firstSeenAt = 1,
        totalCents = cents, basePayCents = null, tipCents = null, proven = false,
    )

    private fun covers(seq: Long, cmd: String = "M1", evidence: String = "receipt:other", cents: Long = 500) =
        ActiveMatchRow(cmd, evidence, seq, ReceiptMatchRole.COVERED, cents, matchedAt = 50)

    private fun clears(seq: Long, cmd: String = "M1") =
        ActiveMatchRow(cmd, "receipt:other", seq, ReceiptMatchRole.CLEARED, null, matchedAt = 50)

    // Y1 NONE, Y2 OFFER_PAY $9.00: a one-offer stack.
    private val y1 = row(1)
    private val y2 = row(2, pay = 9.0, basis = PayBasis.OFFER_PAY)

    @Test fun `selectable - estimates and no pay are open, every other basis and every dasher change is not`() {
        assertTrue(ReceiptMatch.selectable(row(1, basis = PayBasis.OFFER_PAY, pay = 5.0), emptyList()))
        assertTrue(ReceiptMatch.selectable(row(1, basis = PayBasis.NONE), emptyList()))
        for (basis in listOf(
            PayBasis.DROP_SHARE, PayBasis.RECEIPT_TOTAL, PayBasis.SUSPECT_FULL_RECEIPT, PayBasis.MANUAL,
            PayBasis.USER_CORRECTED, PayBasis.RECEIPT_MATCHED,
        )) {
            assertFalse(basis, ReceiptMatch.selectable(row(1, basis = basis, pay = 5.0), emptyList()))
        }
        assertFalse(ReceiptMatch.selectable(row(1, basis = PayBasis.OFFER_PAY, pay = 5.0, adjustedAt = 9), emptyList()))
        assertFalse(ReceiptMatch.selectable(row(1, assigned = true), emptyList()))
        assertFalse(ReceiptMatch.selectable(row(1), listOf(covers(1))))
        assertTrue(ReceiptMatch.selectable(row(1), listOf(clears(1))))
    }

    @Test fun `lock - names each reason, first match wins`() {
        assertNull(ReceiptMatch.lock(row(1), emptyList()))
        assertEquals(
            MatchLock.MatchedTo("receipt:other"),
            ReceiptMatch.lock(row(1, basis = PayBasis.RECEIPT_MATCHED, pay = 5.0, adjustedAt = 50), listOf(covers(1))),
        )
        assertEquals(MatchLock.PaidByReceipt, ReceiptMatch.lock(row(1, basis = PayBasis.RECEIPT_TOTAL, pay = 28.51), emptyList()))
        assertEquals(MatchLock.PaidByReceipt, ReceiptMatch.lock(row(1, basis = PayBasis.SUSPECT_FULL_RECEIPT), emptyList()))
        assertEquals(MatchLock.ChangedByYou, ReceiptMatch.lock(row(1, basis = PayBasis.USER_CORRECTED, pay = 5.0), emptyList()))
        assertEquals(MatchLock.ChangedByYou, ReceiptMatch.lock(row(1, basis = PayBasis.MANUAL, pay = 5.0), emptyList()))
        assertEquals(MatchLock.ChangedByYou, ReceiptMatch.lock(row(1, adjustedAt = 9), emptyList()))
        assertEquals(MatchLock.ChangedByYou, ReceiptMatch.lock(row(1, assigned = true), emptyList()))
    }

    @Test fun `suggestion - a one-offer job suggests every open delivery of it`() { // non-vacuous
        assertEquals(setOf(1L, 2L), ReceiptMatch.suggestion(receipt(2), listOf(y1, y2), emptyList()))
    }

    @Test fun `suggestion - a two-offer job suggests the subject alone`() {
        val x1 = row(1, basis = PayBasis.OFFER_PAY, pay = 10.0, hashes = listOf("hX", "hY"))
        val x2 = row(2, basis = PayBasis.OFFER_PAY, pay = 10.0, hashes = listOf("hX", "hY"))
        assertEquals(setOf(2L), ReceiptMatch.suggestion(receipt(2), listOf(x1, x2), emptyList()))
    }

    @Test fun `suggestion - a zero-offer one-drop job suggests the subject`() {
        assertEquals(setOf(1L), ReceiptMatch.suggestion(receipt(1), listOf(row(1, hashes = emptyList())), emptyList()))
    }

    @Test fun `suggestion - a locked or missing subject suggests nothing`() {
        val shared = row(2, basis = PayBasis.DROP_SHARE, pay = 9.0)
        assertTrue(ReceiptMatch.suggestion(receipt(2), listOf(y1, shared), emptyList()).isEmpty())
        assertTrue(ReceiptMatch.suggestion(receipt(9), listOf(y1, y2), emptyList()).isEmpty())
        // The subject's job id must match too.
        assertTrue(ReceiptMatch.suggestion(receipt(2, jobId = "J9"), listOf(y1, y2), emptyList()).isEmpty())
    }

    @Test fun `suggestion - a sibling another match covers is not suggested`() {
        val coveredY1 = row(1, basis = PayBasis.RECEIPT_MATCHED, pay = 5.0, adjustedAt = 50)
        assertEquals(setOf(2L), ReceiptMatch.suggestion(receipt(2), listOf(coveredY1, y2), listOf(covers(1))))
    }

    @Test fun `plan - twenty fifty over two is ten twenty-five each`() { // non-vacuous
        val other = row(3, jobId = "J2", pay = 9.0, basis = PayBasis.RECEIPT_TOTAL)
        val plan = ReceiptMatch.plan(receipt(2), listOf(y1, y2, other), emptyList(), setOf(1, 2))!!
        assertEquals(listOf(1025L, 1025L), plan.covered.map { it.newPayCents })
        assertEquals(listOf(null, 900L), plan.covered.map { it.priorPayCents })
        assertEquals(listOf(PayBasis.NONE, PayBasis.OFFER_PAY), plan.covered.map { it.priorPayBasis })
        assertTrue(plan.cleared.isEmpty())
        assertEquals(1800L, plan.shiftPayBeforeCents)
        assertEquals(2950L, plan.shiftPayAfterCents)
        assertEquals("receipt:t2:1", plan.evidenceId)
        assertEquals(2050L, plan.receiptCents)
    }

    @Test fun `plan - ten dollars over three is 333, 333, 334 in sequence order`() {
        val rows = listOf(row(3), row(1), row(2))
        val plan = ReceiptMatch.plan(receipt(1, cents = 1000), rows, emptyList(), setOf(1, 2, 3))!!
        assertEquals(listOf(1L, 2L, 3L), plan.covered.map { it.deliveryEventSequenceId })
        assertEquals(listOf(333L, 333L, 334L), plan.covered.map { it.newPayCents })
    }

    @Test fun `plan - one-offer job, the subject alone clears its sibling's estimate`() { // non-vacuous
        val y1Est = row(1, pay = 9.0, basis = PayBasis.OFFER_PAY)
        val plan = ReceiptMatch.plan(receipt(2), listOf(y1Est, y2), emptyList(), setOf(2))!!
        assertEquals(listOf(2L), plan.covered.map { it.deliveryEventSequenceId })
        assertEquals(listOf(2050L), plan.covered.map { it.newPayCents })
        assertEquals(listOf(PlannedRow(1, 900, PayBasis.OFFER_PAY, null)), plan.cleared)
        assertEquals(1800L, plan.shiftPayBeforeCents)
        assertEquals(1800L - 900 + (2050 - 900), plan.shiftPayAfterCents)
    }

    @Test fun `plan - two-offer job clears nothing`() {
        val x1 = row(1, basis = PayBasis.OFFER_PAY, pay = 10.0, hashes = listOf("hX", "hY"))
        val x2 = row(2, basis = PayBasis.OFFER_PAY, pay = 10.0, hashes = listOf("hX", "hY"))
        val plan = ReceiptMatch.plan(receipt(1, cents = 1000), listOf(x1, x2), emptyList(), setOf(1))!!
        assertTrue(plan.cleared.isEmpty())
        assertEquals(listOf(1000L), plan.covered.map { it.newPayCents })
    }

    @Test fun `plan - a selected locked row is ignored and an empty selection is null`() {
        val locked = row(3, basis = PayBasis.RECEIPT_TOTAL, pay = 28.51, jobId = "J2")
        val plan = ReceiptMatch.plan(receipt(2), listOf(y1, y2, locked), emptyList(), setOf(2, 3))!!
        assertEquals(listOf(2L), plan.covered.map { it.deliveryEventSequenceId })
        assertNull(ReceiptMatch.plan(receipt(2), listOf(y1, y2), emptyList(), emptySet()))
        assertNull(ReceiptMatch.plan(receipt(2), listOf(y1, y2, locked), emptyList(), setOf(3)))
    }

    @Test fun `holdsWritten - true on the written state, false after any change`() {
        val written = covers(1, cents = 1025)
        val matched = row(1, pay = 10.25, basis = PayBasis.RECEIPT_MATCHED, adjustedAt = 50)
        assertTrue(ReceiptMatch.holdsWritten(written, matched))
        assertFalse(ReceiptMatch.holdsWritten(written, matched.copy(realizedPay = 11.0)))
        assertFalse(ReceiptMatch.holdsWritten(written, matched.copy(payBasis = PayBasis.USER_CORRECTED)))
        assertFalse(ReceiptMatch.holdsWritten(written, matched.copy(driverAdjustedAt = 51)))
        assertFalse(ReceiptMatch.holdsWritten(written, null))
        val cleared = clears(1)
        assertTrue(ReceiptMatch.holdsWritten(cleared, row(1)))
        assertFalse(ReceiptMatch.holdsWritten(cleared, row(1, pay = 4.0, basis = PayBasis.NONE)))
        assertFalse(ReceiptMatch.holdsWritten(cleared, row(1, pay = 5.0, basis = PayBasis.RECEIPT_MATCHED, adjustedAt = 60)))
        assertFalse(ReceiptMatch.holdsWritten(cleared, row(1, adjustedAt = 60)))
    }

    @Test fun `offerCount - distinct hashes over the job's rows`() {
        val rows = listOf(row(1, hashes = listOf("a")), row(2, hashes = listOf("a", "b")), row(3, jobId = "J2", hashes = listOf("c")))
        assertEquals(2, ReceiptMatch.offerCount("J1", rows))
        assertEquals(1, ReceiptMatch.offerCount("J2", rows))
        assertEquals(0, ReceiptMatch.offerCount("J3", rows))
    }

    /** Pinned seed (the repo's property convention): a failure is a reproducible finding. */
    @Test
    fun `evenSplitCents - property - the shares sum to the total and differ by at most the remainder`() {
        val random = kotlin.random.Random(EVEN_SPLIT_SEED)
        repeat(2_000) {
            val count = random.nextInt(1, 13)
            val total = random.nextLong(1, 100_001)
            val shares = DropPayApportioner.evenSplitCents(total, count)
            assertEquals("count for $total/$count", count, shares.size)
            assertEquals("sum for $total/$count", total, shares.sum())
            val spread = shares.max() - shares.min()
            assertTrue("spread $spread for $total/$count", spread <= total % count)
        }
    }

    private companion object {
        const val EVEN_SPLIT_SEED = 1599L
    }
}
