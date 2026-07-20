package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #810 B1 — end-to-end Level-B replay of the **seq-114 invisible-unassign** shape, driving TWO
 * accepted offers folded into ONE job (only the second delivered) through the REAL
 * [cloud.trotter.dashbuddy.core.state.StateMachine] + [cloud.trotter.dashbuddy.core.state.EffectMap],
 * and asserting exactly ONE `JOB_ACCEPT_MISMATCH` fires at the job close — while the delivered drop
 * still folds normally.
 *
 * **Why a reconstruction (the #700 precedent).** The fielded seq-114 unassign committed through a
 * support-chat path that rendered NO confirmation screen or click (Finding 1 in the #810 design), so
 * there is no capturable dasher-side commit frame — a faithful full-device replay is impossible by
 * construction. This test therefore reuses the real, already-PII-swept
 * `single_delivery_2026_06_16` fixture as the DELIVERED order (order B) and adds explicitly-labelled
 * SYNTHETIC injections for the invisibly-abandoned first order (order A) and for the one arrival frame
 * the delivered order's own capture suppressed:
 *  - order A's offer + accept (a mid-pickup add-on that folds into the job via the REAL
 *    `consumeAcceptIntoJob`/`appendAddOn`, giving the job two `acceptedOffers` and a second dropoff
 *    placeholder that is NEVER activated — the seq-114 corpse `121`). A is never unassigned (the
 *    invisible-unassign class emits no `TASK_UNASSIGNED`);
 *  - order B's dropoff ARRIVAL, carrying frame 06's OWN real parsed fields re-stamped as arrived — the
 *    single_delivery capture completed B via a grace retire with no `DELIVERY_ARRIVED` frame on disk
 *    (the arrival fell to UNKNOWN / was deduped, the same suppression #700 documents), so `arrivedAt`
 *    needs this stand-in to make B a fully-delivered, accounted drop.
 *
 * The assertion is a hand-authored correct-behaviour invariant, never `replay == db`: given the machine
 * is driven this way, the job closes with 2 accepts > 1 accounted drop → exactly one tripwire fires,
 * carrying the true counts, and order B's `DELIVERY_COMPLETED` still lands. No new device fixture is
 * committed (the only fixture bytes are the pre-existing swept single_delivery ones), so this test adds
 * no raw customer PII — the injected orders carry only a synthetic hash / frame 06's already-hashed one.
 */
class AcceptMismatchReplayTest {

    private val session = "snapshots/sessions/single_delivery_2026_06_16"

    private fun dd(step: SessionReplay.ReplayStep) = step.stateAfter.regions.platforms[Platform.DoorDash]

    /** A synthetic ADD-ON offer A (the invisibly-abandoned order) — explicitly labelled, no device
     *  fixture; one order, dropoffCount 1, distinct hash so it appends into the delivered job. */
    private fun offerA(atMs: Long): SessionReplay.RawInput = SessionReplay.RawInput(
        Observation.Screen(
            timestamp = atMs, captureId = null, ruleId = "doordash.screen.offer_popup",
            metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
            parsed = ParsedFields.OfferFields(
                parsedOffer = ParsedOffer(offerHash = "synthetic-abandoned-A", payAmount = 28.50, distanceMiles = 5.0),
            ),
            target = "offer_popup",
        ),
        atMs = atMs,
    )

    /** Order B's dropoff ARRIVAL — the frame the single_delivery capture suppressed. Carries frame 06's
     *  OWN real parsed fields (store + customer hash) re-stamped as the arrived sub-flow so `arrivedAt`
     *  is set and B counts as a fully-delivered, accounted drop (the #700 `mamaMargiesArrival` precedent). */
    private fun dropoffArrival(atMs: Long, realFields: ParsedFields.TaskFields): SessionReplay.RawInput =
        SessionReplay.RawInput(
            Observation.Screen(
                timestamp = atMs, captureId = null, ruleId = "doordash.screen.dropoff_photo",
                metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffArrived, modeHint = Mode.Online,
                parsed = realFields.copy(subFlow = TaskSubFlow.ARRIVED), target = "dropoff_photo",
            ),
            atMs = atMs,
        )

    private fun run(): List<SessionReplay.ReplayStep> {
        val screens = SessionReplay.loadSession(session).map { SessionReplay.ScreenInput(it) }
        val click = SessionReplay.loadClickFrame("$session/02_accept_offer_click.json")
        // Order B's real parsed dropoff fields, from frame 06's own production recognition.
        val f06 = screens.first { it.frame.file.startsWith("06_") }.frame
        val bDropFields = SessionReplay.replayRecognition(listOf(f06)).first().parsed as ParsedFields.TaskFields
        // Order A (abandoned) folds in mid-pickup, between 03 pickup_nav (…052151) and 04 arrival (…167944).
        val aOffer = offerA(1_781_644_100_000L)
        val aClick = SessionReplay.syntheticOfferClick("accept_offer", 1_781_644_100_500L)
        // Order B's suppressed arrival, between 06 dropoff_pre_arrival (…692624) and 07 summary (…731531).
        val bArrival = dropoffArrival(1_781_644_700_000L, bDropFields)
        val timer = SessionReplay.graceCommit(screens.maxOf { it.atMs } + 200_000L)
        return SessionReplay.reduceMixed(screens + click + aOffer + aClick + bArrival + timer)
    }

    @Test
    fun `exactly one JOB_ACCEPT_MISMATCH fires at close - 2 accepts, 1 delivered, 1 leftover TBD`() {
        val steps = run()
        val mismatches = steps.flatMap { it.events }.filter { it.type == AppEventType.JOB_ACCEPT_MISMATCH }
        assertEquals("exactly one tripwire fires at the job close", 1, mismatches.size)

        val p = mismatches.single().payload as JobAcceptMismatchPayload
        assertEquals("two offers were accepted into the closing job", 2, p.acceptedCount)
        assertEquals("only the single delivered drop is accounted", 1, p.accountedCount)
        assertTrue("both offer hashes ride the payload", p.acceptedOfferHashes.contains("synthetic-abandoned-A"))
        assertEquals("the abandoned order left one never-activated TBD placeholder", 1, p.leftoverTbdPlaceholders)
        assertEquals("nothing was explicitly unassigned (the INVISIBLE class)", 0, p.unassignedCount)
        assertEquals("the delivered drop's customer hash is carried", 1, p.deliveredCustomerHashes.size)
    }

    @Test
    fun `the delivered drop still folds - order B completes exactly once`() {
        val steps = run()
        val completed = steps.flatMap { it.events }.count { it.type == AppEventType.DELIVERY_COMPLETED }
        assertEquals("order B still delivers normally — the tripwire never disturbs it", 1, completed)
    }

    @Test
    fun `the tripwire fires on the job-close step, not mid-flow`() {
        val steps = run()
        // No mismatch is emitted while the job is still live (activeJob non-null with the same id).
        steps.forEach { s ->
            val fired = s.events.any { it.type == AppEventType.JOB_ACCEPT_MISMATCH }
            if (fired) {
                assertTrue(
                    "the tripwire only fires on the step that closes the job (activeJob cleared/reminted)",
                    dd(s)?.activeJob == null,
                )
            }
        }
    }
}
