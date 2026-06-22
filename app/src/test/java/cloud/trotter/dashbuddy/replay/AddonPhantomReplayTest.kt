package cloud.trotter.dashbuddy.replay

import cloud.trotter.dashbuddy.test.util.SessionReplay
import org.junit.Assert.assertNotEquals
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
 *    no longer masquerade as a delivery summary (it falls through to UNKNOWN — harmless, never
 *    forwarded to the state machine).
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
        assertNotEquals(
            "an add-on offer frame must not classify as a delivery summary (would fabricate a \$0 completion)",
            "delivery_summary_collapsed", obs.target,
        )
    }
}
