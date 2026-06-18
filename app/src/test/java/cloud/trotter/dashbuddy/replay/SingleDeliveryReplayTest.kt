package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end Level-B replay of a real single delivery (2026-06-16, redacted), driving the full
 * accept→pickup→dropoff→complete chain through the REAL [cloud.trotter.dashbuddy.core.state.StateMachine]
 * with an INJECTED accept click and (separately) an injected GRACE_COMMIT timer.
 *
 * This is the "complete" half of the replay harness the per-frame snapshot tests can't reach:
 * - the injected CLICK is what turns the offer outcome into OFFER_ACCEPTED (screen-only resolves it
 *   to OFFER_TIMEOUT — see [withoutTheClickTheOfferTimesOut]);
 * - the chain mints exactly ONE dropoff (the #498 phantom/over-mint guards + #503 dropoff-from-offer
 *   placeholder), which resolves its customer and completes exactly once;
 * - the injected TIMER commits the dropoff retire on a quiet post-receipt tail.
 *
 * Fixtures: `snapshots/sessions/single_delivery_2026_06_16/` — real captures, customer PII redacted
 * via SnapshotRedactor (verified: zero customer name/address tokens survive in any frame). The
 * `02_accept_offer_click.json` envelope is the real `doordash.click.accept_offer` capture; the
 * screen frames are offer_popup → pickup nav/arrival → dropoff nav/arrival → receipt → idle.
 */
class SingleDeliveryReplayTest {

    private val session = "snapshots/sessions/single_delivery_2026_06_16"

    private fun counts(steps: List<SessionReplay.ReplayStep>): Map<AppEventType, Int> =
        steps.flatMap { it.events }.groupingBy { it.type }.eachCount()

    /** Every distinct DROPOFF taskId that ever appears in the region state across the whole trace. */
    private fun distinctDropoffTaskIds(steps: List<SessionReplay.ReplayStep>): Set<String> =
        steps.flatMap { s ->
            val dd = s.stateAfter.regions.platforms[Platform.DoorDash]
            dd?.activeJob?.tasks.orEmpty() + dd?.recentTasks.orEmpty() + listOfNotNull(dd?.activeTask)
        }.filter { it.phase == TaskPhase.DROPOFF }.map { it.taskId }.toSet()

    @Test
    fun `injected accept click drives a full accept-pickup-dropoff-complete chain (#498, #503, #518)`() {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        val steps = SessionReplay.reduceMixed(screens + click)
        val c = counts(steps)

        // One real offer was received (the #498 over-reject guard: a real offer still recognizes).
        assertEquals("exactly one real offer received", 1, c[AppEventType.OFFER_RECEIVED] ?: 0)

        // The INJECTED click is what flips the outcome: OFFER_ACCEPTED, never OFFER_TIMEOUT.
        assertEquals("the injected accept click logs OFFER_ACCEPTED", 1, c[AppEventType.OFFER_ACCEPTED] ?: 0)
        assertEquals("no OFFER_TIMEOUT once the accept click is injected", 0, c[AppEventType.OFFER_TIMEOUT] ?: 0)

        // Exactly ONE dropoff ever exists — no identity-less phantom (#498), no over-mint (#498/#503).
        assertEquals(
            "a single delivery mints exactly one dropoff task across the whole chain",
            1,
            distinctDropoffTaskIds(steps).size,
        )

        // It completes exactly once — no double-count (#518).
        assertEquals("delivery completes exactly once", 1, c[AppEventType.DELIVERY_COMPLETED] ?: 0)

        // And the dropoff resolved a customer (the pre-created placeholder was filled, #503 slice 3).
        val completedDropoff = steps.last().stateAfter.regions.platforms[Platform.DoorDash]
            ?.recentTasks?.lastOrNull { it.phase == TaskPhase.DROPOFF }
        assertTrue("the completed dropoff carries a resolved customer", completedDropoff?.customerNameHash != null)
    }

    @Test
    fun `without the injected click the offer resolves to OFFER_TIMEOUT (the click is load-bearing)`() {
        // Screen-only: the job still forms (screen-flow transition), but with no click recording the
        // accept intent the outcome is OFFER_TIMEOUT — which is exactly what the injection fixes.
        val steps = SessionReplay.reduce(session)
        val c = counts(steps)
        assertEquals("screen-only offer times out", 1, c[AppEventType.OFFER_TIMEOUT] ?: 0)
        assertEquals("screen-only never logs OFFER_ACCEPTED", 0, c[AppEventType.OFFER_ACCEPTED] ?: 0)
        // The job/dropoff STRUCTURE is identical either way (clicks don't build the dropoff chain).
        assertEquals("dropoff structure is click-independent", 1, distinctDropoffTaskIds(steps).size)
    }

    @Test
    fun `an injected GRACE_COMMIT timer commits the dropoff retire on a quiet post-receipt tail`() {
        // No trailing idle frame → the session goes quiet after the receipt. The injected timer
        // retires the dropoff via lazy expiry (DELIVERY_CONFIRMED), which a screen-only quiet tail
        // could never reach. (DELIVERY_COMPLETED additionally needs a PostTask→non-PostTask flow
        // exit, demonstrated by the idle frame in the full-chain test above.)
        val screens = SessionReplay.loadSession(session)
            .filterNot { it.file.contains("waiting_for_offer") }
            .map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        val receiptMs = screens.maxOf { it.atMs }
        val timer = SessionReplay.graceCommit(receiptMs + 2_500L + 1L)
        val steps = SessionReplay.reduceMixed(screens + click + timer)
        val c = counts(steps)

        assertEquals("the GRACE_COMMIT timer commits the dropoff retire", 1, c[AppEventType.DELIVERY_CONFIRMED] ?: 0)
        assertEquals("still exactly one dropoff", 1, distinctDropoffTaskIds(steps).size)
        assertEquals("the accept click still logged OFFER_ACCEPTED", 1, c[AppEventType.OFFER_ACCEPTED] ?: 0)
    }
}
