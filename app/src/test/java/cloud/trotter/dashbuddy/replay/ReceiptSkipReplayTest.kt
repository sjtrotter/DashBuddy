package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #596 end-to-end Level-B replay of a real receipt-skip (2026-06-29, redacted): a delivered dropoff
 * whose post-delivery receipt DoorDash never rendered — the next offer chained straight over it.
 *
 * Real capture sequence (`snapshots/sessions/receipt_skip_2026_06_29/`, customer PII redacted
 * "Deliver to [redacted]" style so the customer hash stays non-null and the #498 guards are
 * satisfied):
 *  - `01_dropoff_navigation` → `02_dropoff_handoff` — the drop (ARRIVED, carries the customer)
 *  - `03_waiting_for_offer` — the receipt-skip moment: task→idle arms the TASK_RETIRE grace
 *  - injected `graceCommit` past the grace deadline — commits the retire; #596 T1 closes the
 *    physically-complete job even though no receipt (PostTask) ever appeared
 *  - `04_offer_popup` + injected accept click + `05_pickup_navigation` — the next INDEPENDENT offer,
 *    which must start a NEW job, not fold into the never-closed one (the field-observed absorption)
 *
 * `05` is the nearest real 06-29 pickup task frame (H-E-B), re-stamped to fall after the offer/click
 * (there was no real pickup after the offer — it was declined in the field; we inject the accept to
 * exercise T2's new-job path). Pre-#596 this asserts RED: same jobId, zero completions.
 */
class ReceiptSkipReplayTest {

    private val session = "snapshots/sessions/receipt_skip_2026_06_29"

    private fun buildSteps(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session)
        val waitingMs = screens.first { it.file.contains("waiting_for_offer") }.capturedAtMs
        val offerMs = screens.first { it.file.contains("offer_popup") }.capturedAtMs
        // graceCommit strictly past the TASK_RETIRE deadline (idle arm → gracePeriodMs = 10s) and
        // before the offer pops; the accept click sits between the offer and the pickup pop.
        val inputs = screens.map { SessionReplay.ScreenInput(it) } +
            SessionReplay.graceCommit(waitingMs + 10_600L) +
            SessionReplay.syntheticOfferClick(OfferIntent.ACCEPT, offerMs + 500L)
        return SessionReplay.reduceMixed(inputs)
    }

    @Test
    fun `a receipt-skipped drop completes exactly once and the next offer starts a NEW job (#596)`() {
        val steps = buildSteps()
        val completions = steps.flatMap { it.events }.filter { it.type == AppEventType.DELIVERY_COMPLETED }

        // Exactly ONE completion — minted at job close, not on a (never-rendered) receipt.
        assertEquals("the receipt-skipped drop completes exactly once", 1, completions.size)
        val payload = completions.single().payload as DeliveryPayload
        // Receipt-less completion: no receipt fields → null pay (#528's territory), never fabricated.
        assertNull("a receipt-less completion carries no pay", payload.totalPay)
        assertNotNull("the completed drop kept its (redacted) customer identity", payload.customerHash)
        val closedJobId = payload.jobId

        // The job closed on the grace-commit step (T1), BEFORE the next accept.
        val graceStep = steps.first { it.observation is Observation.Timeout }
        assertNull(
            "T1 closes the physically-complete job on the retire commit (no receipt needed)",
            graceStep.stateAfter.regions.platforms[Platform.DoorDash]?.activeJob,
        )

        // The injected accept logged OFFER_ACCEPTED and minted a NEW job — absorption broken.
        assertTrue(
            "the injected accept resolves the offer",
            steps.flatMap { it.events }.any { it.type == AppEventType.OFFER_ACCEPTED },
        )
        val newJob = steps.last().stateAfter.regions.platforms[Platform.DoorDash]?.activeJob
        assertNotNull("the next offer starts a job", newJob)
        assertNotEquals(
            "the next accepted offer is a NEW job, not absorbed into the never-closed one",
            closedJobId, newJob?.jobId,
        )
    }
}
