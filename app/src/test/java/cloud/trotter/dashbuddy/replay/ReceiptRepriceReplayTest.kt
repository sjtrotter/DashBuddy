package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.test.util.SessionReplay
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

    @Test
    fun `layer 1 — the fielded 3_9s expansion now lands INSIDE the grace, so the completion is receipt-priced`() {
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
        val payload = completed.single()
        assertNotNull(
            "the completion carries the EXPANDED receipt's itemization (#1033 layer 1)\n" +
                SessionReplay.trace(steps),
            payload.parsedPay,
        )
        assertNotNull(
            "…and therefore an apportioned share — the fold's DROP_SHARE arm keys on exactly this",
            payload.dropRealizedPay,
        )
        assertEquals(
            "a sole drop's share IS the receipt total",
            payload.parsedPay!!.total,
            payload.dropRealizedPay!!,
            0.005,
        )
        // The stepper no longer tries to know that the mint already carried this itemization (#1033
        // review round 8 — two rounds of trying refused legitimate corrections), so it may still emit
        // a REDUNDANT "the receipt says X". What matters is that it says the same X: the projector
        // compares the row and no-ops it, leaving the row byte-identical.
        reprices(steps).forEach {
            assertEquals(
                "a redundant re-price must carry the SAME itemization the completion did",
                payload.parsedPay,
                it.parsedPay,
            )
        }
    }

    @Test
    fun `layer 2 — an expansion that still lands after the completion re-prices the drop`() {
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
        assertNull(
            "the completion was minted off the COLLAPSED receipt — no itemization\n" +
                SessionReplay.trace(steps),
            completed.single().parsedPay,
        )

        val repriced = reprices(steps)
        assertEquals(
            "the late expansion emits exactly one re-price for the delivered drop\n" +
                SessionReplay.trace(steps),
            1,
            repriced.size,
        )
        val e = repriced.single()
        assertEquals("it targets the drop the completion recorded", completed.single().taskId, e.taskId)
        assertEquals(completed.single().jobId, e.jobId)
        assertEquals(
            "a sole drop's re-price share IS the receipt total",
            e.parsedPay.total,
            e.dropRealizedPay,
            0.005,
        )
    }
}
