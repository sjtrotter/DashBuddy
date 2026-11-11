package cloud.trotter.log.gig.replay

import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.state.ReceiptProofGap
import cloud.trotter.log.gig.domain.state.ReceiptScope
import cloud.trotter.log.gig.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1033 — end-to-end Level-B replay of the collapsed-receipt seam, over the REAL 2026-08-23 DoorDash
 * 8.93.7 receipt captures (`delivery_summary_collapsed/…17-35-17-361…c65d43` and
 * `delivery_summary_expanded/…17-35-21-221…6b9dd1`) spliced into the real single-delivery
 * accept→pickup→dropoff chain.
 *
 * The field shape those two frames recorded: the receipt rendered COLLAPSED (a total, no
 * itemization), and the expansion landed **3.86 s later** — 1.36 s past the 2.5 s authoritative
 * retire grace. So the completion committed off the collapsed receipt and the drop was priced by the
 * #691 `OFFER_PAY` estimate, while the real receipt sat on screen a second later.
 *
 * Both halves of the fix are proved against those same two frames:
 *  - **Layer 1** — the collapsed receipt's window is now 8 s, so the expansion lands inside it and
 *    the `DELIVERY_COMPLETED` carries the itemization + an apportioned `dropRealizedPay` (which is
 *    exactly what makes the fold stamp `DROP_SHARE` instead of `OFFER_PAY`).
 *  - **Layer 2** — if the expansion arrives after the completion anyway, a
 *    `DELIVERY_RECEIPT_REPRICE` carries the receipt to the read model instead.
 *
 * The offset between the two spliced frames is the REAL one (3.86 s); only the base instant is
 * chosen, so the timing under test is the field's, not the test's.
 *
 * **#1598 (OQ1):** the chain's offer is the legacy View card, which shows no drop count, so every receipt here is
 * UNPROVEN (`CARD_COUNT_MISSING`): it places no money, removes no estimate, and is saved as `RECEIPT_OBSERVED`
 * evidence. The layer 1 and layer 2 mechanics stay covered on proven fixtures in `:core:state`
 * (`ReceiptRepriceTest`, `LateReceiptGuardTest`).
 */
class ReceiptRepriceReplayTest {

    private val session = "snapshots/sessions/single_delivery_2026_06_16"
    private val collapsedFrame =
        "snapshots/delivery_summary_collapsed/" +
            "2026-08-23_17-35-17-361__doordash__accessibility.window__delivery_summary_collapsed__c65d43.json"
    private val expandedFrame =
        "snapshots/delivery_summary_expanded/" +
            "2026-08-23_17-35-21-221__doordash__accessibility.window__delivery_summary_expanded__6b9dd1.json"

    /** The fielded gap between the collapsed frame and its expansion (17:35:17.361 → 17:35:21.221). */
    private val fieldedExpandLatencyMs = 3_860L

    /** The chain up to (but excluding) the receipt: offer → accept click → pickup → dropoff arrival. */
    private fun chainBeforeTheReceipt(): List<SessionReplay.ReplayInput> {
        val screens = SessionReplay.loadSession(session)
            .filterNot { it.file.contains("delivery_summary") || it.file.contains("waiting_for_offer") }
            .map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        return screens + click
    }

    /** The session's own trailing idle frame, re-stamped — the PostTask exit that mints the completion. */
    private fun idleAt(atMs: Long): SessionReplay.ScreenInput {
        val idle = SessionReplay.loadSession(session).first { it.file.contains("waiting_for_offer") }
        return SessionReplay.ScreenInput(idle.copy(capturedAtMs = atMs))
    }

    private fun completions(steps: List<SessionReplay.ReplayStep>): List<DeliveryPayload> =
        steps.flatMap { it.events }
            .filter { it.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.payload as DeliveryPayload }

    private fun reprices(steps: List<SessionReplay.ReplayStep>): List<DeliveryReceiptRepricePayload> =
        steps.flatMap { it.events }
            .filter { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
            .map { it.payload as DeliveryReceiptRepricePayload }

    private fun evidence(steps: List<SessionReplay.ReplayStep>): List<ReceiptObservedPayload> =
        steps.flatMap { it.events }
            .filter { it.type == AppEventType.RECEIPT_OBSERVED }
            .map { it.payload as ReceiptObservedPayload }

    /** #1598: an unproven receipt leaves no receipt money on the completion and marks it UNPROVEN. */
    private fun assertNoReceiptMoney(payload: DeliveryPayload, steps: List<SessionReplay.ReplayStep>) {
        assertNull("no itemization\n" + SessionReplay.trace(steps), payload.parsedPay)
        assertNull("no bare total", payload.totalPay)
        assertNull("no share", payload.dropRealizedPay)
        assertEquals(ReceiptScope.UNPROVEN, payload.receiptScope)
        assertNotNull("the drop keeps the offer estimate", payload.offerPayShare)
    }

    @Test
    fun `layer 1 — #1598 the expansion inside the grace is evidence only on the legacy card`() {
        val base = chainBeforeTheReceipt()
        val t0 = base.maxOf { it.atMs } + 1_000L

        val steps = SessionReplay.reduceMixed(
            base +
                SessionReplay.ScreenInput(SessionReplay.loadScreenFrame(collapsedFrame, t0)) +
                // The OLD authoritative deadline (t0 + 2.5 s): this timer must NOT commit any more.
                SessionReplay.graceCommit(t0 + 2_501L) +
                SessionReplay.ScreenInput(
                    SessionReplay.loadScreenFrame(expandedFrame, t0 + fieldedExpandLatencyMs),
                ) +
                // The deadline the expansion tightened it back to (+2.5 s from the expanded frame).
                SessionReplay.graceCommit(t0 + fieldedExpandLatencyMs + 2_501L) +
                idleAt(t0 + fieldedExpandLatencyMs + 4_000L),
        )

        val completed = completions(steps)
        assertEquals("the delivery completes exactly once", 1, completed.size)
        assertNoReceiptMoney(completed.single(), steps)
        assertTrue("no re-price", reprices(steps).isEmpty())

        // One receipt, one episode: the collapsed read and its expansion are two revisions of one record.
        val ev = evidence(steps)
        assertEquals(listOf(1, 2), ev.map { it.revision })
        assertEquals(1, ev.map { it.evidenceId }.toSet().size)
        assertEquals(listOf(false, true), ev.map { it.isExpanded })
        assertTrue(ev.all { it.scope == ReceiptScope.UNPROVEN && it.proofGap == ReceiptProofGap.CARD_COUNT_MISSING })
        assertEquals(completed.single().taskId, ev.first().subjectTaskId)
    }

    @Test
    fun `layer 2 — #1598 an expansion after the completion re-prices nothing on the legacy card`() {
        val base = chainBeforeTheReceipt()
        val t0 = base.maxOf { it.atMs } + 1_000L
        // The receipt stays collapsed past the whole 8 s window, so the completion commits off it.
        val commitAt = t0 + 8_001L
        val exitAt = commitAt + 500L

        val steps = SessionReplay.reduceMixed(
            base +
                SessionReplay.ScreenInput(SessionReplay.loadScreenFrame(collapsedFrame, t0)) +
                SessionReplay.graceCommit(commitAt) +
                idleAt(exitAt) +
                // Only now does the breakdown render.
                SessionReplay.ScreenInput(SessionReplay.loadScreenFrame(expandedFrame, exitAt + 1_000L)),
        )

        val completed = completions(steps)
        assertEquals("the delivery still completes exactly once", 1, completed.size)
        assertNoReceiptMoney(completed.single(), steps)
        assertTrue("no re-price\n" + SessionReplay.trace(steps), reprices(steps).isEmpty())

        // The idle frame ended the first receipt's episode, so the late expansion is a second record, read after
        // the close.
        val ev = evidence(steps)
        assertEquals(2, ev.map { it.evidenceId }.toSet().size)
        assertEquals(listOf(ReceiptProofGap.CARD_COUNT_MISSING, ReceiptProofGap.NO_LIVE_JOB), ev.map { it.proofGap })
    }
}
