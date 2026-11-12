package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.analytics.receipts.PlannedRow
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatchPlan
import cloud.trotter.log.gig.domain.model.event.payload.DeliverySessionAssignPayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchLine
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchUndonePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchedPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeCorrectionPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesCorrectionPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionMilesOperation
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportCorrectionPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportOperation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * #705 F4d — the write-side [CorrectionRepository] `require` boundaries (previously ZERO coverage).
 * Every money/miles field must be **finite** (a non-finite value would reach `AppEventCodec` and throw
 * a `SerializationException` deep in the append), pay finite > 0, tip/cash/miles finite ≥ 0, and
 * `adjustDelivery` needs at least one changed field or a note. A rejected input must throw
 * `IllegalArgumentException` BEFORE any event is appended; a valid one appends exactly once.
 */
class CorrectionRepositoryTest {

    private val appEventRepo: AppEventRepo = mock()
    private val repo = CorrectionRepository(appEventRepo)

    private fun rejects(block: suspend CorrectionRepository.() -> Unit) {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.block() } }
    }

    @Test
    fun `invalid delivery numbers are rejected`() {
        val cases = listOf<Pair<String, suspend CorrectionRepository.() -> Unit>>(
            "addManualDelivery rejects a zero pay" to { manual(pay = 0.0) },
            "addManualDelivery rejects a negative pay" to { manual(pay = -1.0) },
            "addManualDelivery rejects a NaN pay" to { manual(pay = Double.NaN) },
            "addManualDelivery rejects an infinite pay" to { manual(pay = Double.POSITIVE_INFINITY) },
            "addManualDelivery rejects a negative tip" to { manual(pay = 5.0, tip = -0.01) },
            "addManualDelivery rejects an infinite cash tip" to { manual(pay = 5.0, cashTip = Double.POSITIVE_INFINITY) },
            "addManualDelivery rejects a NaN cash tip" to { manual(pay = 5.0, cashTip = Double.NaN) },
            "addManualDelivery rejects a negative miles" to { manual(pay = 5.0, miles = -0.5) },
            "addManualDelivery rejects an infinite miles" to { manual(pay = 5.0, miles = Double.NEGATIVE_INFINITY) },
            "adjustDelivery rejects a zero newPay" to { adjust(newPay = 0.0) },
            "adjustDelivery rejects a negative newPay" to { adjust(newPay = -1.0) },
            "adjustDelivery rejects a NaN newPay" to { adjust(newPay = Double.NaN) },
            "adjustDelivery rejects an infinite newPay" to { adjust(newPay = Double.POSITIVE_INFINITY) },
            "adjustDelivery rejects a negative newTip" to { adjust(newTip = -1.0) },
            "adjustDelivery rejects an infinite newCashTip" to { adjust(newCashTip = Double.NEGATIVE_INFINITY) },
            "adjustDelivery rejects a negative newMiles" to { adjust(newMiles = -0.5) },
            "adjustDelivery rejects a NaN newMiles" to { adjust(newMiles = Double.NaN) },
        )
        cases.forEach { (name, input) ->
            assertThrows(name, IllegalArgumentException::class.java) { runBlocking { repo.input() } }
        }
    }

    // ── addManualDelivery ───────────────────────────────────────────────

    @Test
    fun `addManualDelivery accepts a finite positive pay with zero and null optionals`() = runTest {
        repo.manual(pay = 5.0, tip = 0.0, cashTip = 0.0, miles = 0.0)
        repo.manual(pay = 5.0, tip = null, cashTip = null, miles = null)
        verify(appEventRepo, times(2)).appendUserEvent(any(), anyOrNull())
    }

    // ── adjustDelivery ──────────────────────────────────────────────────

    @Test
    fun `adjustDelivery rejects an empty edit with no note`() = rejects { adjustDelivery(targetEventSequenceId = 1L, sessionId = "S1") }

    @Test
    fun `adjustDelivery accepts a note-only edit and a finite positive pay edit`() = runTest {
        // adjustDelivery returns the appended sequence id (#1333 S3a); the mock must answer one.
        whenever(appEventRepo.appendUserEvent(any(), anyOrNull())).thenReturn(7L)
        repo.adjustDelivery(targetEventSequenceId = 1L, sessionId = "S1", note = "just a note")
        repo.adjust(newPay = 12.0, newTip = 0.0, newCashTip = 0.0, newMiles = 0.0)
        verify(appEventRepo, times(2)).appendUserEvent(any(), anyOrNull())
    }

    // ── assignDeliverySession (#660 piece 2) ────────────────────────────

    @Test
    fun `assignDeliverySession appends the assign event with envelope sessionId == newSessionId`() = runTest {
        repo.assignDeliverySession(targetEventSequenceId = 42L, newSessionId = "S1", note = "was mine")

        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo).appendUserEvent(captor.capture(), anyOrNull())
        val event = captor.firstValue
        assertEquals(AppEventType.DELIVERY_SESSION_ASSIGN, event.type)
        assertEquals("envelope sessionId == newSessionId (attribution/liveness convention)", "S1", event.sessionId)
        val payload = event.payload as DeliverySessionAssignPayload
        assertEquals(42L, payload.targetEventSequenceId)
        assertEquals("S1", payload.newSessionId)
        assertEquals("note passes through", "was mine", payload.note)
    }

    @Test
    fun `assignDeliverySession with a null newSessionId is the unassign (undo) — envelope sessionId null`() = runTest {
        repo.assignDeliverySession(targetEventSequenceId = 7L, newSessionId = null)

        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo).appendUserEvent(captor.capture(), anyOrNull())
        val event = captor.firstValue
        assertNull("unassign carries a null envelope sessionId", event.sessionId)
        val payload = event.payload as DeliverySessionAssignPayload
        assertEquals(7L, payload.targetEventSequenceId)
        assertNull(payload.newSessionId)
        assertNull(payload.note)
    }

    @Test
    fun `assignDeliverySession rejects a blank (non-null) newSessionId and appends nothing`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.assignDeliverySession(targetEventSequenceId = 1L, newSessionId = "   ") }
        }
        verify(appEventRepo, never()).appendUserEvent(any(), anyOrNull())
    }

    // ── correctOfferOutcome (#810 B2 Tier 2) ────────────────────────────

    @Test
    fun `correctOfferOutcome attested appends an ATTESTED correction with no envelope sessionId`() = runTest {
        repo.correctOfferOutcome(targetOfferEventSequenceId = 42L, attested = true, note = "chat unassign")

        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo).appendUserEvent(captor.capture(), anyOrNull())
        val event = captor.firstValue
        assertEquals(AppEventType.OFFER_OUTCOME_CORRECTION, event.type)
        assertNull("an offer-outcome correction targets an offer by PK — no session envelope", event.sessionId)
        val payload = event.payload as OfferOutcomeCorrectionPayload
        assertEquals(42L, payload.targetOfferEventSequenceId)
        assertEquals(OfferOutcomeResolution.UNASSIGNED_ATTESTED, payload.resolvedOutcome)
        assertEquals("chat unassign", payload.note)
    }

    @Test
    fun `correctOfferOutcome undo appends a null-resolution correction`() = runTest {
        repo.correctOfferOutcome(targetOfferEventSequenceId = 7L, attested = false)

        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo).appendUserEvent(captor.capture(), anyOrNull())
        val payload = captor.firstValue.payload as OfferOutcomeCorrectionPayload
        assertEquals(7L, payload.targetOfferEventSequenceId)
        assertNull("undo clears the resolution", payload.resolvedOutcome)
    }

    @Test
    fun `a rejected addManualDelivery appends nothing`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.manual(pay = 0.0) } }
        verify(appEventRepo, never()).appendUserEvent(any(), anyOrNull())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private suspend fun CorrectionRepository.manual(
        pay: Double,
        tip: Double? = null,
        cashTip: Double? = null,
        miles: Double? = null,
    ) = addManualDelivery(
        sessionId = "S1", storeName = "StoreX", pay = pay, tip = tip, cashTip = cashTip,
        completedAt = 1_000L, miles = miles, note = null,
    )

    private suspend fun CorrectionRepository.adjust(
        newPay: Double? = null,
        newTip: Double? = null,
        newCashTip: Double? = null,
        newMiles: Double? = null,
    ) = adjustDelivery(
        targetEventSequenceId = 1L, sessionId = "S1",
        newPay = newPay, newTip = newTip, newCashTip = newCashTip, newMiles = newMiles,
    )
    @Test
    fun `session report validation rejects invalid operations and values before append`() = runTest {
        rejects { correctSessionReport("483", SessionReportOperation.SET) }
        for (value in listOf(10_000.01, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            rejects { correctSessionReport("483", SessionReportOperation.SET, value) }
        }
        rejects { correctSessionReport("483", SessionReportOperation.CLEAR, 0.0) }
        rejects { correctSessionReport("483", SessionReportOperation.RESTORE_MACHINE, 0.0) }
        rejects { correctSessionReport("483", "UNKNOWN") }
        verify(appEventRepo, never()).appendUserEvent(any(), anyOrNull())
    }

    @Test
    fun `session report appends the target session and preserves operation value and note`() = runTest {
        val cases = listOf(
            SessionReportOperation.SET to 0.0,
            SessionReportOperation.SET to 10_000.0,
            SessionReportOperation.CLEAR to null,
            SessionReportOperation.RESTORE_MACHINE to null,
        )
        cases.forEach { (op, value) -> repo.correctSessionReport("483", op, value, "note") }
        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo, times(cases.size)).appendUserEvent(captor.capture(), anyOrNull())
        captor.allValues.zip(cases).forEach { (event, case) ->
            assertEquals(AppEventType.SESSION_REPORT_CORRECTION, event.type)
            assertEquals("483", event.sessionId)
            assertEquals(SessionReportCorrectionPayload("483", case.first, case.second, "note"), event.payload)
        }
    }

    @Test
    fun `shift miles reject an invalid value before appending`() = runTest {
        for (bad in listOf(-0.1, 2_000.01, Double.NaN, Double.POSITIVE_INFINITY)) rejects { setShiftMiles("1152", bad) }
        verify(appEventRepo, never()).appendUserEvent(any(), anyOrNull())
    }

    @Test
    fun `shift miles append SET and RESTORE_MACHINE for the target shift`() = runTest {
        repo.setShiftMiles("1152", 2.5, "note")
        repo.setShiftMiles("1152", 0.0)
        repo.restoreShiftMiles("1152")
        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo, times(3)).appendUserEvent(captor.capture(), anyOrNull())
        assertEquals(
            listOf(
                SessionMilesCorrectionPayload("1152", SessionMilesOperation.SET, 2.5, "note"),
                SessionMilesCorrectionPayload("1152", SessionMilesOperation.SET, 0.0),
                SessionMilesCorrectionPayload("1152", SessionMilesOperation.RESTORE_MACHINE),
            ),
            captor.allValues.map { it.payload },
        )
        captor.allValues.forEach {
            assertEquals(AppEventType.SESSION_MILES_CORRECTION, it.type)
            assertEquals("1152", it.sessionId)
        }
    }

    // ── #1599 receipt match ─────────────────────────────────────────────

    private val plan = ReceiptMatchPlan(
        evidenceId = "receipt:t2:1", receiptCents = 2050,
        covered = listOf(PlannedRow(7, 900, "OFFER_PAY", 2050)),
        cleared = listOf(PlannedRow(6, 900, "OFFER_PAY", null)),
        shiftPayBeforeCents = 1800, shiftPayAfterCents = 2050,
    )

    @Test
    fun `matchReceipt appends one RECEIPT_MATCHED equal to the plan, covered lines then cleared`() = runTest {
        repo.matchReceipt("S1", "cmd-1", plan)
        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo).appendUserEvent(captor.capture(), anyOrNull())
        val event = captor.firstValue
        assertEquals(AppEventType.RECEIPT_MATCHED, event.type)
        assertEquals("S1", event.sessionId)
        assertEquals(
            ReceiptMatchedPayload(
                commandId = "cmd-1", sessionId = "S1", evidenceId = "receipt:t2:1", receiptCents = 2050,
                lines = listOf(
                    ReceiptMatchLine(7, ReceiptMatchRole.COVERED, 900, "OFFER_PAY", 2050),
                    ReceiptMatchLine(6, ReceiptMatchRole.CLEARED, 900, "OFFER_PAY", null),
                ),
            ),
            event.payload,
        )
    }

    @Test
    fun `matchReceipt rejects each malformed plan and appends nothing`() = runTest {
        val bad = listOf(
            "blank command" to (" " to plan),
            "no covered row" to ("cmd" to plan.copy(covered = emptyList())),
            "zero receipt" to ("cmd" to plan.copy(receiptCents = 0, covered = listOf(PlannedRow(7, null, "NONE", 0)))),
            "null share" to ("cmd" to plan.copy(covered = listOf(PlannedRow(7, null, "NONE", null)))),
            "negative share" to ("cmd" to plan.copy(covered = listOf(PlannedRow(7, null, "NONE", -1), PlannedRow(8, null, "NONE", 2051)))),
            "shares off the receipt" to ("cmd" to plan.copy(covered = listOf(PlannedRow(7, null, "NONE", 2049)))),
            "cleared with pay" to ("cmd" to plan.copy(cleared = listOf(PlannedRow(6, 900, "OFFER_PAY", 1)))),
            "a delivery twice" to ("cmd" to plan.copy(cleared = listOf(PlannedRow(7, 900, "OFFER_PAY", null)))),
        )
        for ((name, input) in bad) {
            assertThrows(name, IllegalArgumentException::class.java) { runBlocking { repo.matchReceipt("S1", input.first, input.second) } }
        }
        verify(appEventRepo, never()).appendUserEvent(any(), anyOrNull())
    }

    @Test
    fun `undoReceiptMatch appends one undo naming the match`() = runTest {
        repo.undoReceiptMatch("S1", "cmd-1")
        val captor = argumentCaptor<cloud.trotter.log.gig.domain.model.event.AppEvent>()
        verify(appEventRepo).appendUserEvent(captor.capture(), anyOrNull())
        assertEquals(AppEventType.RECEIPT_MATCH_UNDONE, captor.firstValue.type)
        assertEquals("S1", captor.firstValue.sessionId)
        assertEquals(ReceiptMatchUndonePayload("S1", "cmd-1"), captor.firstValue.payload)
    }
}
