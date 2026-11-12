package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.EventMetadata
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchLine
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchUndonePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchedPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionReportCorrectionPayload
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1599: the receipt match and undo folds judge an ended shift at their log position and carry no context. */
class ReceiptMatchFoldsTest {
    private val ended = SessionFoldContext("S1", Platform.DoorDash, 100L, 200L, endedAt = 200L)
    private val notEnded = "RECEIPT_MATCHED: target shift unknown or not ended at this log position"
    private val malformed = "RECEIPT_MATCHED: malformed match"

    private fun ev(type: AppEventType, payload: AppEventPayload?, envelopeSession: String? = "S1") = SequencedAppEvent(
        sequenceId = 42,
        event = AppEvent(type, 999_999L, envelopeSession, payload),
        metadata = EventMetadata(odometer = 999.0),
    )

    private fun covered(seq: Long, cents: Long?) = ReceiptMatchLine(seq, ReceiptMatchRole.COVERED, null, PayBasis.NONE, cents)
    private fun cleared(seq: Long, cents: Long? = null) = ReceiptMatchLine(seq, ReceiptMatchRole.CLEARED, 900, PayBasis.OFFER_PAY, cents)

    private fun match(
        lines: List<ReceiptMatchLine> = listOf(covered(7, 1025), cleared(5), covered(6, 1025)),
        receiptCents: Long = 2050,
        commandId: String = "cmd-1",
        sessionId: String = "S1",
    ) = ReceiptMatchedPayload(commandId, sessionId, "receipt:t7:1", receiptCents, lines)

    private fun foldMatch(payload: AppEventPayload?, context: SessionFoldContext? = ended, envelope: String? = "S1") =
        RecordFolds.foldEvent(ev(AppEventType.RECEIPT_MATCHED, payload, envelope), context, null)

    @Test fun `both events are bookkeeping`() {
        assertTrue(AppEventType.RECEIPT_MATCHED in RecordFolds.BOOKKEEPING_EVENTS)
        assertTrue(AppEventType.RECEIPT_MATCH_UNDONE in RecordFolds.BOOKKEEPING_EVENTS)
    }

    @Test fun `a good match folds the decision with no context, covered in payload order`() { // non-vacuous
        val outcome = foldMatch(match())
        assertNull(outcome.context)
        assertNull(outcome.skip)
        assertEquals(
            ReceiptMatchFold(
                commandId = "cmd-1", sessionId = "S1", evidenceId = "receipt:t7:1", receiptCents = 2050,
                covered = listOf(7L to 1025L, 6L to 1025L), previewCleared = setOf(5L), matchedAt = 999_999L, sequenceId = 42,
            ),
            outcome.receiptMatch,
        )
    }

    @Test fun `a missing or foreign payload is skipped`() {
        assertEquals("RECEIPT_MATCHED: missing/malformed payload", foldMatch(null).skip)
        assertEquals(
            "RECEIPT_MATCHED: missing/malformed payload",
            foldMatch(SessionReportCorrectionPayload("S1", "SET", 1.0)).skip,
        )
    }

    @Test fun `another shift, an unknown shift or a running shift is skipped`() {
        for (outcome in listOf(
            foldMatch(match(), envelope = "other"),
            foldMatch(match(), envelope = null),
            foldMatch(match(sessionId = "other")),
            foldMatch(match(), context = null),
            foldMatch(match(), context = ended.copy(sessionId = "other")),
            foldMatch(match(), context = ended.copy(endedAt = null)),
        )) {
            assertEquals(notEnded, outcome.skip)
            assertNull(outcome.receiptMatch)
            assertNull(outcome.context)
        }
    }

    @Test fun `each malformed match is skipped`() {
        val cases = listOf(
            match(commandId = " "),
            match(receiptCents = 0),
            match(lines = listOf(cleared(5)), receiptCents = 2050),
            match(lines = listOf(covered(7, 1025), covered(7, 1025))),
            match(lines = listOf(covered(7, 1025), cleared(7))),
            match(lines = listOf(covered(7, null), covered(6, 2050))),
            match(lines = listOf(covered(7, -1), covered(6, 2051))),
            match(lines = listOf(covered(7, 2050), cleared(5, cents = 0))),
            match(lines = listOf(covered(7, 1025), covered(6, 1024))),
        )
        for (payload in cases) {
            val outcome = foldMatch(payload)
            assertEquals(payload.toString(), malformed, outcome.skip)
            assertNull(outcome.receiptMatch)
        }
    }

    @Test fun `the undo folds its decision, and skips a bad target or a blank command id`() {
        val good = RecordFolds.foldEvent(ev(AppEventType.RECEIPT_MATCH_UNDONE, ReceiptMatchUndonePayload("S1", "cmd-1")), ended, null)
        assertNull(good.context)
        assertNull(good.skip)
        assertEquals(ReceiptMatchUndoFold("S1", "cmd-1", 999_999L, 42), good.receiptMatchUndo)

        assertEquals(
            "RECEIPT_MATCH_UNDONE: missing/malformed payload",
            RecordFolds.foldEvent(ev(AppEventType.RECEIPT_MATCH_UNDONE, null), ended, null).skip,
        )
        val unknown = "RECEIPT_MATCH_UNDONE: target shift unknown or not ended at this log position"
        assertEquals(
            unknown,
            RecordFolds.foldEvent(ev(AppEventType.RECEIPT_MATCH_UNDONE, ReceiptMatchUndonePayload("S1", "cmd-1")), ended.copy(endedAt = null), null).skip,
        )
        assertEquals(
            unknown,
            RecordFolds.foldEvent(ev(AppEventType.RECEIPT_MATCH_UNDONE, ReceiptMatchUndonePayload("S1", "cmd-1"), "other"), ended, null).skip,
        )
        val blank = RecordFolds.foldEvent(ev(AppEventType.RECEIPT_MATCH_UNDONE, ReceiptMatchUndonePayload("S1", "")), ended, null)
        assertEquals("RECEIPT_MATCH_UNDONE: malformed undo", blank.skip)
        assertNull(blank.receiptMatchUndo)
    }
}
