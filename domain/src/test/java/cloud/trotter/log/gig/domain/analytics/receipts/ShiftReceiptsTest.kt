package cloud.trotter.log.gig.domain.analytics.receipts

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1599: [ShiftReceipts] judges each saved receipt OPEN, MATCHED or SETTLED against the shift's rows. */
class ShiftReceiptsTest {

    private fun row(seq: Long, pay: Double? = null, basis: String = PayBasis.NONE, adjustedAt: Long? = null) = DeliveryRecord(
        eventSequenceId = seq, sessionId = "S", platform = Platform.DoorDash, jobId = "J1", taskId = "t$seq",
        storeName = "Store A", completedAt = seq * 100, realizedPay = pay, payBasis = basis, netProfit = null,
        realizedMiles = null, realizedMinutes = null, tip = null, basePay = null, driverAdjustedAt = adjustedAt,
        jobOfferHashes = listOf("hY"),
    )

    private fun receipt(subjectSeq: Long, firstSeenAt: Long = 1, proven: Boolean = false, id: String = "receipt:t$subjectSeq:$firstSeenAt") =
        SavedReceipt(id, "J1", "t$subjectSeq", firstSeenAt, 2050, null, null, proven)

    private fun active(evidence: String, seq: Long, cmd: String = "M1", cents: Long = 1025) =
        ActiveMatchRow(cmd, evidence, seq, ReceiptMatchRole.COVERED, cents, matchedAt = 50)

    private val matchedRow = { seq: Long -> row(seq, pay = 10.25, basis = PayBasis.RECEIPT_MATCHED, adjustedAt = 50) }

    @Test fun `a proven receipt is settled`() {
        val out = ShiftReceipts.of(listOf(receipt(1, proven = true)), listOf(row(1)), emptyList())
        assertEquals(ReceiptStatus.SETTLED, out.items.single().status)
        assertTrue(out.open.isEmpty())
    }

    @Test fun `an unproven receipt with an active match is matched and undoable`() { // non-vacuous
        val r = receipt(2)
        val out = ShiftReceipts.of(
            listOf(r), listOf(matchedRow(1), matchedRow(2)), listOf(active(r.evidenceId, 1), active(r.evidenceId, 2)),
        )
        val item = out.items.single()
        assertEquals(ReceiptStatus.MATCHED, item.status)
        assertEquals("M1", item.matchCommandId)
        assertTrue(item.undoable)
        assertEquals(mapOf(1L to "M1", 2L to "M1"), out.undoByDelivery)
    }

    @Test fun `a pay edit on a covered row keeps it matched, not undoable`() {
        val r = receipt(2)
        val edited = row(1, pay = 12.0, basis = PayBasis.USER_CORRECTED, adjustedAt = 70)
        val out = ShiftReceipts.of(listOf(r), listOf(edited, matchedRow(2)), listOf(active(r.evidenceId, 1), active(r.evidenceId, 2)))
        val item = out.items.single()
        assertEquals(ReceiptStatus.MATCHED, item.status)
        assertFalse(item.undoable)
        assertTrue(out.undoByDelivery.isEmpty())
    }

    @Test fun `a repeated read whose delivery another receipt's match covers is settled`() { // non-vacuous
        val first = receipt(1, firstSeenAt = 1)
        val second = receipt(2, firstSeenAt = 2)
        val out = ShiftReceipts.of(
            listOf(first, second), listOf(matchedRow(1), matchedRow(2)),
            listOf(active(first.evidenceId, 1), active(first.evidenceId, 2)),
        )
        assertEquals(listOf(ReceiptStatus.MATCHED, ReceiptStatus.SETTLED), out.items.map { it.status })
        assertTrue(out.open.isEmpty())
    }

    @Test fun `a subject with receipt money is settled, one with an estimate or missing is open`() {
        assertEquals(
            ReceiptStatus.SETTLED,
            ShiftReceipts.of(listOf(receipt(1)), listOf(row(1, 9.0, PayBasis.DROP_SHARE)), emptyList()).items.single().status,
        )
        assertEquals(
            ReceiptStatus.OPEN,
            ShiftReceipts.of(listOf(receipt(1)), listOf(row(1, 9.0, PayBasis.OFFER_PAY)), emptyList()).items.single().status,
        )
        assertEquals(ReceiptStatus.OPEN, ShiftReceipts.of(listOf(receipt(9)), listOf(row(1)), emptyList()).items.single().status)
    }

    @Test fun `items are in first-seen order, then evidence id`() {
        val late = receipt(1, firstSeenAt = 30)
        val early = receipt(2, firstSeenAt = 10)
        val tieA = receipt(3, firstSeenAt = 20, id = "a")
        val tieB = receipt(4, firstSeenAt = 20, id = "b")
        val out = ShiftReceipts.of(listOf(late, tieB, early, tieA), emptyList(), emptyList())
        assertEquals(listOf(early.evidenceId, "a", "b", late.evidenceId), out.items.map { it.receipt.evidenceId })
        assertEquals(2050L, out.totalCentsOf("a"))
        assertNull(out.totalCentsOf("missing"))
    }

    @Test fun `undoByDelivery holds only the undoable match's covered rows`() {
        val r = receipt(2)
        val clearedRow = ActiveMatchRow("M1", r.evidenceId, 1, ReceiptMatchRole.CLEARED, null, matchedAt = 50)
        val out = ShiftReceipts.of(
            listOf(r), listOf(row(1), row(2, pay = 20.5, basis = PayBasis.RECEIPT_MATCHED, adjustedAt = 50)),
            listOf(clearedRow, active(r.evidenceId, 2, cents = 2050)),
        )
        assertTrue(out.items.single().undoable)
        assertEquals(mapOf(2L to "M1"), out.undoByDelivery)
    }
}
