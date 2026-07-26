package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #786 — regression teeth for `uber.click.decline_offer` against REAL device click envelopes,
 * plus per-predicate mutation coverage.
 *
 * Fixtures live under `src/test/resources/clicks/uber/` (deliberately NOT under `snapshots/`,
 * whose sub-directories are screen-intent goldens enumerated by `GoldenSnapshotRegressionTest`).
 * Each is a `click_context.v1` envelope — `payload = {node, screenTarget}` — so the test feeds the
 * production [TestRulesetFactory] click ruleset exactly what the runtime classifier sees.
 *
 * Provenance:
 *  - `2026-07-23_18-45-24-*` / `2026-07-24_18-43-18-*` — real DECLINE taps on the focused Uber
 *    offer card (106x106 anonymous childless `android.widget.Button`, `screenTarget: "offer"`).
 *    Verbatim, unedited — these envelopes carry no text/desc/id at all, so there is nothing to
 *    redact.
 *  - `2026-07-21_18-39-25-*` — the real ACCEPT interaction from the same surface: the offer
 *    `androidx.cardview.widget.CardView` itself, whose subtree carries "Match". The card's dropoff
 *    cross-street line was replaced with the corpus's standing placeholder ("Sample St & Sample
 *    Ave, San Antonio"); merchant/pay/time text is driver-owned and kept verbatim.
 *  - `synthetic__nag_not_now__*` / `synthetic__nag_settings__*` — click-shaped envelopes built
 *    around the two Button nodes lifted VERBATIM from the fielded permission-nag capture
 *    `accessibility.window/offer/2026-07-23_19-41-54-465__…__11cc18.json`: an **offer-classified**
 *    frame carrying a nag sheet whose "Settings"/"Not now" buttons are leaf, id-less and clickable
 *    — i.e. they satisfy every predicate in the rule EXCEPT the own-text-empty guard. Only the
 *    envelope wrapper is synthetic; the nodes are real. Android system strings, zero PII.
 */
class UberDeclineClickRuleTest {

    private companion object {
        const val RULE_ID = "uber.click.decline_offer"

        val DECLINE_TAPS = listOf(
            "2026-07-23_18-45-24-066__uber__accessibility.click__UNKNOWN__7e26a0.json",
            "2026-07-24_18-43-18-986__uber__accessibility.click__UNKNOWN__0e1a01.json",
        )

        const val ACCEPT_CARD_TAP =
            "2026-07-21_18-39-25-627__uber__accessibility.click__UNKNOWN__015073.json"

        val NAG_BUTTONS = listOf(
            "synthetic__nag_not_now__from_2026-07-23_19-41-54-465.json",
            "synthetic__nag_settings__from_2026-07-23_19-41-54-465.json",
        )
    }

    private val fixtures = File("src/test/resources/clicks/uber")

    private data class ClickFixture(val name: String, val node: UiNode, val screenTarget: String?)

    private fun load(name: String): ClickFixture {
        val file = File(fixtures, name)
        assertTrue("missing fixture: ${file.absolutePath}", file.exists())
        val payload = Json.parseToJsonElement(file.readText()).jsonObject.getValue("payload").jsonObject
        return ClickFixture(
            name = name,
            node = TestResourceLoader.nodeFromElement(payload.getValue("node")),
            screenTarget = payload["screenTarget"]?.jsonPrimitive?.content,
        )
    }

    private fun match(node: UiNode, screenTarget: String?) =
        TestRulesetFactory.clickRuleset.matchFirst(node, screenTarget = screenTarget)

    private fun classify(fixture: ClickFixture) = match(fixture.node, fixture.screenTarget)

    // =========================================================================
    // Positive: the anonymous childless Button on the offer card IS the decline commit
    // =========================================================================

    @Test
    fun `real Uber offer-card dismiss taps classify as decline_offer`() {
        for (name in DECLINE_TAPS) {
            val fixture = load(name)
            assertEquals("$name: fixture must be an offer-screen click", "offer", fixture.screenTarget)
            val result = classify(fixture)
            assertEquals(
                "$name: expected the #786 decline-commit rule to claim this tap",
                RULE_ID,
                result?.ruleId,
            )
            assertEquals(
                "$name: must carry the decline-COMMIT intent (latches declineCommittedAt, #594)",
                OfferIntent.DECLINE,
                result?.intent,
            )
        }
    }

    @Test
    fun `the decline node really is anonymous, childless and Button-classed`() {
        // Pins the field evidence the rule anchors on — if a future capture refresh changes the
        // node's shape, this fails loudly next to the rule instead of silently widening it.
        for (name in DECLINE_TAPS) {
            val node = load(name).node
            assertEquals("$name: class", "android.widget.Button", node.className)
            assertEquals("$name: must be clickable", true, node.isClickable)
            assertTrue("$name: must carry no view id", node.viewIdResourceName.isNullOrBlank())
            assertTrue("$name: must be a leaf", node.children.isEmpty())
            assertTrue("$name: must carry no own text", node.text.isNullOrBlank())
            assertTrue("$name: must carry no text of any kind", node.allText.isEmpty())
        }
    }

    // =========================================================================
    // Negative 1: the accept interaction (CardView, subtree-labelled) must NEVER decline
    // =========================================================================

    @Test
    fun `the CardView accept tap does NOT match the decline rule`() {
        val fixture = load(ACCEPT_CARD_TAP)
        assertEquals("fixture must be an offer-screen click", "offer", fixture.screenTarget)
        val result = classify(fixture)
        assertNotEquals(
            "the accept CardView tap must never be claimed by the decline rule — " +
                "a false decline latch would kill the accepted offer (#594 commit precedence)",
            RULE_ID,
            result?.ruleId,
        )
        assertNotEquals(
            "the accept CardView tap must never carry the decline intent",
            OfferIntent.DECLINE,
            result?.intent,
        )
    }

    @Test
    fun `the accept tap is structurally separable — non-leaf CardView with a labelled subtree`() {
        val node = load(ACCEPT_CARD_TAP).node
        assertEquals("androidx.cardview.widget.CardView", node.className)
        assertTrue("the accept tap is NOT a leaf", node.children.isNotEmpty())
        assertTrue(
            "the accept tap's subtree carries the accept label",
            node.allText.any {
                it.equals("Match", ignoreCase = true) || it.equals("Accept", ignoreCase = true)
            },
        )
    }

    // =========================================================================
    // Negative 2: unrelated chrome on an offer-CLASSIFIED frame (the review's blocking HIGH)
    // =========================================================================

    @Test
    fun `permission-nag buttons on an offer-classified frame do NOT match`() {
        // These are leaf, id-less, clickable android.widget.Buttons on screenTarget "offer" — they
        // satisfy every predicate in the rule except "own text is empty". Without that guard a
        // "Not now" tap would latch an irreversible false decline on a live pending offer.
        for (name in NAG_BUTTONS) {
            val fixture = load(name)
            val node = fixture.node
            assertEquals("$name: same class as the X", "android.widget.Button", node.className)
            assertEquals("$name: same clickability as the X", true, node.isClickable)
            assertTrue("$name: same id-lessness as the X", node.viewIdResourceName.isNullOrBlank())
            assertTrue("$name: same leafness as the X", node.children.isEmpty())
            assertTrue("$name: but it IS labelled", !node.text.isNullOrBlank())
            assertEquals("$name: and it is on an offer-classified frame", "offer", fixture.screenTarget)

            assertNotEquals(
                "$name: labelled chrome on an offer frame must never be claimed as a decline",
                RULE_ID,
                classify(fixture)?.ruleId,
            )
        }
    }

    // =========================================================================
    // Mutation coverage: every structural predicate is INDIVIDUALLY load-bearing
    // =========================================================================

    /**
     * Each mutation changes exactly ONE attribute of the real decline node (or its screen scope)
     * and must break the match, so deleting the corresponding predicate from the rule turns exactly
     * one of these red. No predicate can be dropped silently.
     *
     * The `hasAnyText` Accept/Match guards are deliberately NOT listed: on a LEAF node `allText`
     * can only ever contain that node's own text, so those guards are strictly subsumed by the
     * own-text-empty predicate for every shape `isLeaf: true` admits. They are kept as
     * belt-and-suspenders for a hypothetical non-leaf variant and documented as such in the rule —
     * claiming mutation coverage for them would be theater.
     */
    @Test
    fun `every structural predicate is individually load-bearing`() {
        val base = load(DECLINE_TAPS.first())
        assertEquals("precondition: the unmutated node matches", RULE_ID, match(base.node, base.screenTarget)?.ruleId)

        val mutants: List<Triple<String, UiNode, String?>> = listOf(
            Triple(
                "isClickable: a non-clickable twin",
                base.node.copy(isClickable = false),
                base.screenTarget,
            ),
            Triple(
                "hasNoId: an id-bearing X variant",
                base.node.copy(viewIdResourceName = "com.ubercab.driver:id/dismiss_button"),
                base.screenTarget,
            ),
            Triple(
                "hasClassName: an ImageButton-classed twin",
                base.node.copy(className = "android.widget.ImageButton"),
                base.screenTarget,
            ),
            Triple(
                // The injected child is itself text-free, so own-text/allText stay empty and
                // leafness is the ONLY attribute that differs from the real X.
                "isLeaf: a Button wrapping a (text-free) child",
                base.node.copy(children = listOf(UiNode(className = "android.widget.ImageView"))),
                base.screenTarget,
            ),
            Triple(
                "own text empty: the fielded nag-sheet label",
                base.node.copy(text = "Not now"),
                base.screenTarget,
            ),
            Triple(
                "screenIs: the same node tapped on the awaiting_offer board",
                base.node,
                "awaiting_offer",
            ),
            Triple(
                "screenIs: the same node tapped mid-trip",
                base.node,
                "active_trip",
            ),
        )

        for ((label, node, screenTarget) in mutants) {
            assertNotEquals(
                "mutation '$label' must NOT be claimed by $RULE_ID — that predicate is not load-bearing",
                RULE_ID,
                match(node, screenTarget)?.ruleId,
            )
        }
    }

    @Test
    fun `the anonymous X on a non-offer screen falls through to UNKNOWN entirely`() {
        // Not merely "not a decline": no other click rule claims it either, so it stays an UNKNOWN
        // click — the safe direction, where a missed decline degrades to OFFER_TIMEOUT rather than
        // fabricating one.
        val base = load(DECLINE_TAPS.first())
        assertNull(
            "the bare X on active_trip must match no click rule at all",
            match(base.node, "active_trip"),
        )
    }
}
