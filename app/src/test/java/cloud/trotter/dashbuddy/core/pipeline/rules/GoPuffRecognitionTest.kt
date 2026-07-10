package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * #501 — the GoPuff (DoorDash Drive) warehouse batch-pickup screens. The original three (bin-scan
 * steps, wait survey, barcode failure) are BRANCHES of existing pickup rules, run against the real
 * (redacted) 2026-06-14 capture frames; the item-3 zone-arrival intent (below) is a STANDALONE
 * recognize-only rule exercised via hand-built trees until a real capture lands in the corpus.
 *
 * The load-bearing assertion: the **bin-scan steps** screen mints the batch's single
 * `PICKUP_ARRIVED`. The whole warehouse leg was otherwise all-UNKNOWN, so no arrival ever fired and
 * the state machine jumped nav→confirmed (the #501 root). The wait-survey and barcode-failure
 * screens are recognize-only — they keep the bin-scan stretch out of UNKNOWN without perturbing the
 * pickup phase.
 */
class GoPuffRecognitionTest {

    private val rules = TestRulesetFactory.screenRuleset

    private fun load(path: String): UiNode =
        TestResourceLoader.loadNode(File("src/test/resources/snapshots/$path"))

    @Test
    fun `the GoPuff bin-scan steps screen is the pickup-arrival anchor (#501)`() {
        val r = rules.matchFirst(load("pickup_steps/gopuff_bin_scan_steps.json"))
        assertEquals("recognized as pickup_steps (the GoPuff branch)", "pickup_steps", r?.intent)
        assertEquals(
            "the bin-scan steps screen declares task:pickup:arrived — the batch's one PICKUP_ARRIVED",
            Flow.TaskPickupArrived,
            r?.flow,
        )
    }

    @Test
    fun `the GoPuff at-store wait survey recognizes without changing phase (#501)`() {
        val r = rules.matchFirst(load("pickup_wait_survey/gopuff_wait_survey.json"))
        assertEquals("pickup_wait_survey", r?.intent)
        assertNull("the at-store wait survey is recognize-only — it must not perturb the pickup phase", r?.flow)
    }

    @Test
    fun `the GoPuff barcode-scan failure recognizes without changing phase (#501)`() {
        val r = rules.matchFirst(load("pickup_barcode_scan_issue/gopuff_barcode_scan_issue.json"))
        assertEquals("pickup_barcode_scan_issue", r?.intent)
        assertNull("a mid-pickup sub-screen must not change phase", r?.flow)
    }

    /**
     * #501 item 3 — the GoPuff (Drive) zone-arrival CTA card, recognize-only (dev decision
     * 2026-07-07): no parse, no state.flow — nav is already established before this frame
     * appears, and its button is an affordance, not the arrival anchor (`pickup_steps`'s
     * bin-scan branch owns the one PICKUP_ARRIVED). Unlike the other GoPuff #501 fixtures, no
     * raw device capture for this screen made it into `snapshots/INBOX/` before this build —
     * the anchor strings are the grounded, verbatim-cited strings from issue #501's 2026-06-15
     * deep-dive over the real 2026-06-14 captures, not a fabricated screen. The fixture mirrors
     * the REAL tree shape from the committed pre-arrival corpus (the same CTA widget): the
     * `go_to_store_action_view` node is a text-less container and the label lives two levels
     * down (`primary_action_button` > `textView_prism_button_title`) — which is why the rule's
     * two require clauses are independent tree-wide exists, a property the fall-through tests
     * below pin from the reject side. Hand-built trees exercise the rule's actual predicate
     * logic (same `Ruleset.matchFirst` entry point as a loaded snapshot); they are intentionally
     * NOT filed under `snapshots/` since they aren't captured device trees. `pickup_zone_arrival`
     * is pinned in `ParseOutputGoldenTest.knownUncoveredIntents` until a real capture lands.
     *
     * NOTE the anchors are NOT GoPuff-unique (every regular pickup_pre_arrival tree carries the
     * id + a descendant 'Arrived at store'): a full regular pre-arrival frame is kept out of
     * this rule by priority order (71 first), and a DEGRADED regular frame — one that fails
     * 71's 'Pickup from' conjunct — is kept out by the rule's rejects (customer-card ids,
     * 'Return to dash'), so it falls to UNKNOWN instead of a redact-less recognized capture.
     */
    private fun zoneArrivalCta(label: String): UiNode =
        UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/go_to_store_action_view",
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/primary_action_button",
                    isClickable = true,
                    isEnabled = true,
                    children = listOf(
                        UiNode(
                            viewIdResourceName = "com.doordash.driverapp:id/textView_prism_button_title",
                            text = label,
                        ),
                    ),
                ),
            ),
        )

    private fun zoneArrivalTree(label: String, vararg siblings: UiNode): UiNode =
        UiNode(
            children = listOf(UiNode(text = "2 orders • 3 items total")) + siblings + zoneArrivalCta(label),
        ).restoreParents()

    @Test
    fun `the GoPuff zone-arrival CTA recognizes without changing phase (#501 item 3)`() {
        val r = rules.matchFirst(zoneArrivalTree("Navigate to zone"))
        assertEquals("pickup_zone_arrival", r?.intent)
        assertNull("the zone-arrival CTA is recognize-only — it must not perturb the pickup phase", r?.flow)
    }

    @Test
    fun `the GoPuff zone-arrival CTA also recognizes once its label flips to Arrived at store (#501 item 3)`() {
        val r = rules.matchFirst(zoneArrivalTree("Arrived at store"))
        assertEquals("pickup_zone_arrival", r?.intent)
        assertNull("the zone-arrival CTA is recognize-only — it must not perturb the pickup phase", r?.flow)
    }

    @Test
    fun `a degraded regular pickup frame with a customer card must NOT fall through to the zone rule (#501 item 3)`() {
        // A regular pre-arrival tree whose 'Pickup from' header drifted (fails rule 71's
        // conjunct) but whose CTA + customer card survive. Without the reject this would land
        // on the redact-less zone rule and a raw customer name could persist in a recognized
        // capture (the split-node shape CustomerTextMarkers documents as rule-redact-only).
        val degraded = UiNode(
            children = listOf(
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/user_name_label",
                    text = "Heading to Walgreens",
                ),
                UiNode(
                    viewIdResourceName = "com.doordash.driverapp:id/user_name",
                    text = "Jane D",
                ),
                zoneArrivalCta("Arrived at store"),
            ),
        ).restoreParents()

        val r = rules.matchFirst(degraded)
        assertNotEquals(
            "a frame carrying a customer card must never classify as the redact-less zone rule",
            "pickup_zone_arrival",
            r?.intent,
        )
    }

    @Test
    fun `an expanded mid-pickup map keeps on_dash_map and its online modeHint (#501 item 3)`() {
        // 106 evaluates before on_dash_map (110); the 'Return to dash' reject keeps a map frame
        // that still mounts the pickup CTA sheet with the mode-hint-bearing rule.
        val mapWithCta = UiNode(
            children = listOf(
                UiNode(text = "Return to dash"),
                zoneArrivalCta("Arrived at store"),
            ),
        ).restoreParents()

        val r = rules.matchFirst(mapWithCta)
        assertEquals("on_dash_map", r?.intent)
    }
}
