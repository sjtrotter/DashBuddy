package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SessionReplay] regression for the #564 "add-on phantom" (06-21 dash, seq98).
 *
 * When a **Burger King add-on** offer was accepted mid-stack, its transient frame was misrecognized
 * as `delivery_summary_collapsed`: the rule's `require` keyed on `"this offer"`, which the offer's
 * "Your Silver status gave you priority for **this offer**." line satisfies, and the rejects covered
 * only pickup markers. That false summary drove a PostTask-exit which fabricated a $0, customer-less
 * `DELIVERY_COMPLETED` of the in-flight (grace-retired) Smoky Mo's PICKUP — corrupting the ledger.
 *
 * Defense-in-depth fix, two independent guards:
 *  - **recognition** (this test): `delivery_summary_collapsed` now rejects the offer-only markers
 *    `"High paying offer"` / `"Total will be higher"` / `"Additional"`, so an add-on offer frame can
 *    no longer masquerade as a delivery summary. (#888 update: it no longer falls through to
 *    UNKNOWN either — this is a post-accept teardown frame, the Accept footer already gone, so
 *    `doordash.screen.offer_accepting` now claims it. That rule is RECOGNIZE-ONLY: no `state.flow`,
 *    no parse, so the frame still cannot drive a summary exit or any other lifecycle edge; the
 *    assertion below is unchanged and still the guard that matters.)
 *  - **state** ([cloud.trotter.dashbuddy.core.state.EffectMapTest], #564): a PostTask exit only
 *    completes a task that actually reached `TaskPhase.DROPOFF`, so a retired PICKUP can never
 *    complete even if some other frame trips the exit.
 *
 * Fixture: the real redacted de5eb2 capture (the live app tagged it `delivery_summary_collapsed` —
 * the bug). `assignment_id_text` is DoorDash's opaque offer id, not customer PII; the merchant name
 * is not PII under the privacy model.
 */
class AddonPhantomReplayTest {

    private val session = "snapshots/sessions/addon_phantom_2026_06_21"

    @Test
    fun `the Burger King add-on offer frame is not misrecognized as a delivery summary (#564, Level A)`() {
        val obs = SessionReplay.replayRecognition(session).single()
        println("add-on frame → target=${obs.target} parsed=${obs.parsed::class.simpleName}")
        // #947: pin the EXPECTED intent instead of only excluding the old bug's misfire.
        // As of #888 this fixture is Shape B of `doordash.screen.offer_accepting` — the
        // rule's own comment names this exact fixture
        // (snapshots/sessions/addon_phantom_2026_06_21) as the frame it was written to catch.
        // Verified correct by reading the rule (matchers/rules/doordash/offer.json5): Shape B's
        // `require` demands `accept_decline_fragment_container` + `secondary_action_button_dash_plus`
        // + a non-empty `display_name` that isn't "Customer dropoff"/"Business handoff" — i.e. the
        // offer chrome is still on screen with the Accept footer torn down and a real store leg
        // present, exactly the post-accept teardown frame this fixture captures. The rule is
        // RECOGNIZE-ONLY (no `state.flow`/parse/bind), so classifying here cannot drive any
        // lifecycle edge — recognition merely graduates the frame out of UNKNOWN, which is the
        // guard this test exists to pin. NOTE: #935 may re-anchor `delivery_summary_collapsed`'s
        // `require`, which could shift what this fixture classifies as — revisit this pin then.
        assertEquals(
            "the add-on offer teardown frame's classification drifted; it must never be " +
                "delivery_summary_collapsed (would fabricate a \$0 completion)",
            "offer_accepting", obs.target,
        )
    }
}
