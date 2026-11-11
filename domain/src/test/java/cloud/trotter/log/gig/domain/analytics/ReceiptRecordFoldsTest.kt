package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.EventMetadata
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #1598 — the receipt marker's fold gate and the `RECEIPT_OBSERVED` evidence fold.
 *
 * A separate class rather than more of `RecordFoldsTest` (already past 2 600 lines, principle 3); its name keeps it
 * inside the `*RecordFoldsTest*` filter.
 */
class ReceiptRecordFoldsTest {

    private var seq = 0L

    private fun ev(type: AppEventType, sid: String?, at: Long, payload: AppEventPayload?, odometer: Double? = null) =
        SequencedAppEvent(
            sequenceId = ++seq,
            event = AppEvent(type = type, occurredAt = at, sessionId = sid, payload = payload),
            metadata = odometer?.let { EventMetadata(odometer = it) },
        )

    private fun dashStart(sid: String, at: Long) = ev(
        AppEventType.DASH_START, sid, at,
        SessionStartPayload(sid, Platform.DoorDash.name, startedAt = at, source = SessionStartSource.INTERACTION, startScreen = "x"),
        odometer = 0.0,
    )

    private val receipt = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", 8.0)),
        customerTips = listOf(ParsedPayItem("Store A", 12.0)),
    )

    private fun completed(sid: String, scope: ReceiptScope?, offerPayShare: Double?) = ev(
        AppEventType.DELIVERY_COMPLETED, sid, 4_000,
        DeliveryPayload(
            jobId = "J1", taskId = "T1", storeName = "Store A", customerHash = "cust-T1", addressHash = "addr-T1",
            phaseStartedAt = 3_000, arrivedAt = 3_500, completedAt = 4_000,
            totalPay = 20.0, parsedPay = receipt, offerPayShare = offerPayShare, receiptScope = scope,
        ),
        odometer = 5.0,
    )

    private fun fold(vararg events: SequencedAppEvent): List<FoldOutcome> {
        var ctx: SessionFoldContext? = null
        return events.map { e -> RecordFolds.foldEvent(e, ctx, null).also { ctx = it.context ?: ctx } }
    }

    @Test
    fun `an UNPROVEN completion carrying a stray receipt folds as no receipt, a null marker as history`() {
        val estimate = fold(dashStart("S1", 1_000), completed("S1", ReceiptScope.UNPROVEN, offerPayShare = 9.0))[1].delivery!!
        assertEquals(PayBasis.OFFER_PAY, estimate.payBasis)
        assertEquals(9.0, estimate.realizedPay!!, 1e-9)
        assertNull("no tip from an unproven receipt", estimate.tip)

        val none = fold(dashStart("S2", 1_000), completed("S2", ReceiptScope.UNPROVEN, offerPayShare = null))[1].delivery!!
        assertEquals(PayBasis.NONE, none.payBasis)
        assertNull(none.realizedPay)

        val history = fold(dashStart("S3", 1_000), completed("S3", null, offerPayShare = null))[1].delivery!!
        assertEquals("a pre-#1598 payload folds as it always did", PayBasis.RECEIPT_TOTAL, history.payBasis)
        assertEquals(20.0, history.realizedPay!!, 1e-9)
        assertEquals(12.0, history.tip!!, 1e-9)

        val proven = fold(dashStart("S4", 1_000), completed("S4", ReceiptScope.PROVEN_SINGLE, offerPayShare = null))[1].delivery!!
        assertEquals(history.payBasis, proven.payBasis)
        assertEquals(history.realizedPay, proven.realizedPay)
    }

    @Test
    fun `RECEIPT_OBSERVED folds a receipt record and passes the context through untouched`() {
        val ctx = RecordFolds.foldEvent(dashStart("S5", 1_000), null, null).context
        val event = ev(
            AppEventType.RECEIPT_OBSERVED, "S5", 9_000,
            ReceiptObservedPayload(
                evidenceId = "receipt:T1:5000", revision = 2, platform = Platform.DoorDash.wire, jobId = "J1",
                subjectTaskId = "T1", firstSeenAt = 5_000, observedAt = 9_000, totalPay = 20.0, parsedPay = receipt,
                isExpanded = true, scope = ReceiptScope.UNPROVEN, proofGap = ReceiptProofGap.OFFER_COUNT,
            ),
            odometer = 42.0,
        )
        val out = RecordFolds.foldEvent(event, ctx, null)
        assertEquals("bookkeeping: no liveness advance, no odometer measurement", ctx, out.context)
        assertNull(out.delivery)
        assertNull(out.receiptReprice)
        val r = out.receipt!!
        assertEquals("receipt:T1:5000", r.evidenceId)
        assertEquals("S5", r.sessionId)
        assertEquals(Platform.DoorDash.wire, r.platform)
        assertEquals("J1", r.jobId)
        assertEquals("T1", r.subjectTaskId)
        assertEquals(5_000L, r.firstSeenAt)
        assertEquals(9_000L, r.lastSeenAt)
        assertEquals(2_000L, r.totalCents)
        assertEquals(800L, r.basePayCents)
        assertEquals(1_200L, r.tipCents)
        assertEquals(true, r.isExpanded)
        assertEquals("UNPROVEN", r.scope)
        assertEquals("OFFER_COUNT", r.proofGap)
        assertEquals(2, r.revision)
        assertEquals(event.sequenceId, r.lastEventSequenceId)
    }

    @Test
    fun `a collapsed RECEIPT_OBSERVED has no base or tip, and a malformed one is a counted skip`() {
        val collapsed = RecordFolds.foldEvent(
            ev(
                AppEventType.RECEIPT_OBSERVED, "S6", 5_000,
                ReceiptObservedPayload(
                    evidenceId = "receipt:T1:5000", revision = 1, platform = Platform.DoorDash.wire, jobId = "J1",
                    subjectTaskId = "T1", firstSeenAt = 5_000, observedAt = 5_000, totalPay = 20.0,
                    isExpanded = false, scope = ReceiptScope.PROVEN_SINGLE,
                ),
            ),
            null, null,
        ).receipt!!
        assertNull(collapsed.basePayCents)
        assertNull(collapsed.tipCents)
        assertNull(collapsed.proofGap)
        assertEquals("PROVEN_SINGLE", collapsed.scope)

        val malformed = RecordFolds.foldEvent(ev(AppEventType.RECEIPT_OBSERVED, "S6", 5_000, null), null, null)
        assertNull(malformed.receipt)
        assertNotNull(malformed.skip)
    }

    @Test
    fun `an UNPROVEN re-price is a counted skip, a PROVEN one re-prices`() {
        fun reprice(scope: ReceiptScope?) = RecordFolds.foldEvent(
            ev(
                AppEventType.DELIVERY_RECEIPT_REPRICE, "S7", 6_000,
                DeliveryReceiptRepricePayload(
                    jobId = "J1", taskId = "T1", totalPay = 20.0, parsedPay = receipt, dropRealizedPay = 20.0,
                    receiptScope = scope,
                ),
            ),
            null, null,
        )
        val unproven = reprice(ReceiptScope.UNPROVEN)
        assertNull(unproven.receiptReprice)
        assertEquals("DELIVERY_RECEIPT_REPRICE: unproven scope", unproven.skip)
        assertNotNull(reprice(ReceiptScope.PROVEN_SINGLE).receiptReprice)
        assertNotNull("a pre-#1598 re-price folds as before", reprice(null).receiptReprice)
    }
}
