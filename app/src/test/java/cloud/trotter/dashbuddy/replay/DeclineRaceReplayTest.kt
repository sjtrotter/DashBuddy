package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionReplay] regression for the #594 "decline race": on 2026-06-30 16:59 the dasher DECLINED a
 * Burger King $6.25 offer, confirmed it on the confirm sheet (server-side commit), then — via
 * DoorDash's "Review offer" affordance — hit Accept ~1.2s later. The machine trusted the last click
 * and logged `OFFER_ACCEPTED`, contradicting DoorDash's own dash summary (3 accepts, $45.75, no
 * $6.25; no job/pickup ever formed). The fix: a DECLINE-intent click (which can only come from the
 * confirm sheet, since the `decline_offer` click rule is screen-scoped there) latches
 * `PendingOffer.declineCommittedAt`, and `EffectMap.resolveOfferOutcome` checks the latch FIRST —
 * so a later Accept click can no longer un-decline a committed decline.
 *
 * Fixtures: `snapshots/sessions/decline_race_2026_06_30/` — real device captures from the incident
 * (customer-PII-scanned; the navigation frame's turn-by-turn road names were redacted — it is a
 * route back to a zone, not a customer address). Click envelopes weren't persisted that week (#597),
 * so the three clicks (initial decline → confirm commit → the racing accept) are synthetic, injected
 * while the offer card is up, exactly like the field sequence.
 *   `01_offer_popup_bk` — the BK $6.25 offer (flow OfferPresented, pushes the pending offer)
 *   `04_navigation_pop` — the post-decision navigation frame (turn-by-turn branch → flow idle, so
 *       this is the frame that pops the offer and resolves it DECLINED)
 *   `05_dash_along_the_way` — repositioning to zone (flow idle) — a redundant idle tail; the offer
 *       has already popped by here. The assertions are outcome-count based, so they hold whichever
 *       idle frame does the popping.
 */
class DeclineRaceReplayTest {

    private val session = "snapshots/sessions/decline_race_2026_06_30"

    @Test
    fun `a committed decline stands even when Accept is clicked after it (#594, Level B)`() {
        val frames = SessionReplay.loadSession(session)
        val offerFrame = frames.first { it.file.contains("offer_popup") }
        val t0 = offerFrame.capturedAtMs

        val inputs = listOf(
            SessionReplay.ScreenInput(offerFrame),
            // The offer-screen first decline — a separate intent (no OfferIntent constant); must NOT
            // latch and must fall through as a non-outcome.
            SessionReplay.syntheticOfferClick("initial_decline", atMs = t0 + 2_000L),
            // The confirm-sheet commit — this is the server-side decline; it latches the outcome.
            SessionReplay.syntheticOfferClick(OfferIntent.DECLINE, atMs = t0 + 3_000L),
            // The "Review offer"→Accept race ~1.2s later — must NOT override the committed decline.
            SessionReplay.syntheticOfferClick(OfferIntent.ACCEPT, atMs = t0 + 5_000L),
        ) + frames.filterNot { it.file.contains("offer_popup") }.map { SessionReplay.ScreenInput(it) }

        val steps = SessionReplay.reduceMixed(inputs.sortedBy { it.atMs })
        println(SessionReplay.trace(steps))
        val events = steps.flatMap { it.events }

        assertEquals(
            "exactly one OFFER_RECEIVED (the BK offer)",
            1, events.count { it.type == AppEventType.OFFER_RECEIVED },
        )
        assertEquals(
            "the committed decline resolves to exactly one OFFER_DECLINED",
            1, events.count { it.type == AppEventType.OFFER_DECLINED },
        )
        assertEquals(
            "the racing Accept must NOT log OFFER_ACCEPTED (the pre-fix red signal)",
            0, events.count { it.type == AppEventType.OFFER_ACCEPTED },
        )
        assertEquals(
            "no job/pickup ever formed — the decline stood",
            0, events.count { it.type.name.startsWith("PICKUP") },
        )

        // The forensic payload records that Accept came after the decline was committed.
        val declined = events.first { it.type == AppEventType.OFFER_DECLINED }
        val description = (declined.payload as OfferPayload).description
        assertTrue(
            "the OFFER_DECLINED payload describes the decline race, got: $description",
            description?.contains("decline", ignoreCase = true) == true,
        )
    }
}
