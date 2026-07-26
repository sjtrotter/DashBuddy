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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #786 — regression teeth for `uber.click.decline_offer` against REAL device click envelopes.
 *
 * Fixtures live under `src/test/resources/clicks/uber/` (deliberately NOT under `snapshots/`,
 * whose sub-directories are screen-intent goldens enumerated by `GoldenSnapshotRegressionTest`).
 * Each is a verbatim `click_context.v1` envelope — `payload = {node, screenTarget}` — so the test
 * feeds the production [TestRulesetFactory][cloud.trotter.dashbuddy.test.util.TestRulesetFactory]
 * click ruleset exactly what the runtime classifier sees.
 *
 * Provenance:
 *  - `2026-07-23_18-45-24-*` / `2026-07-24_18-43-18-*` — two dasher DECLINE taps on the focused
 *    Uber offer card (106x106 anonymous childless `android.widget.Button`, `screenTarget: "offer"`).
 *    Verbatim, unedited — these envelopes carry no text/desc/id at all, so there is nothing to
 *    redact.
 *  - `2026-07-21_18-39-25-*` — the ACCEPT interaction from the same surface: the offer
 *    `androidx.cardview.widget.CardView` itself, whose subtree carries "Match". This is the
 *    NEGATIVE pin — the whole separability argument for the rule. The card's dropoff cross-street
 *    line was replaced with the corpus's standing placeholder ("Sample St & Sample Ave, San
 *    Antonio"); merchant/pay/time text is driver-owned data and is kept verbatim.
 */
class UberDeclineClickRuleTest {

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

    private fun classify(fixture: ClickFixture) =
        TestRulesetFactory.clickRuleset.matchFirst(fixture.node, screenTarget = fixture.screenTarget)

    private val declineTaps = listOf(
        "2026-07-23_18-45-24-066__uber__accessibility.click__UNKNOWN__7e26a0.json",
        "2026-07-24_18-43-18-986__uber__accessibility.click__UNKNOWN__0e1a01.json",
    )

    private val acceptCardTap =
        "2026-07-21_18-39-25-627__uber__accessibility.click__UNKNOWN__015073.json"

    // =========================================================================
    // Positive: the anonymous childless Button on the offer card IS the decline commit
    // =========================================================================

    @Test
    fun `real Uber offer-card dismiss taps classify as decline_offer`() {
        for (name in declineTaps) {
            val fixture = load(name)
            assertEquals("$name: fixture must be an offer-screen click", "offer", fixture.screenTarget)
            val match = classify(fixture)
            assertEquals(
                "$name: expected the #786 decline-commit rule to claim this tap",
                "uber.click.decline_offer",
                match?.ruleId,
            )
            assertEquals(
                "$name: must carry the decline-COMMIT intent (latches declineCommittedAt, #594)",
                OfferIntent.DECLINE,
                match?.intent,
            )
        }
    }

    @Test
    fun `the decline node really is anonymous, childless and Button-classed`() {
        // Pins the field evidence the rule anchors on — if a future capture refresh changes the
        // node's shape, this fails loudly next to the rule instead of silently widening it.
        for (name in declineTaps) {
            val node = load(name).node
            assertEquals("$name: class", "android.widget.Button", node.className)
            assertEquals("$name: must be clickable", true, node.isClickable)
            assertTrue("$name: must carry no view id", node.viewIdResourceName.isNullOrBlank())
            assertTrue("$name: must be a leaf", node.children.isEmpty())
            assertTrue("$name: must carry no text of any kind", node.allText.isEmpty())
        }
    }

    // =========================================================================
    // Negative: the accept interaction (CardView, subtree-labelled) must NEVER decline
    // =========================================================================

    @Test
    fun `the CardView accept tap does NOT match the decline rule`() {
        val fixture = load(acceptCardTap)
        assertEquals("fixture must be an offer-screen click", "offer", fixture.screenTarget)
        val match = classify(fixture)
        assertNotEquals(
            "the accept CardView tap must never be claimed by the decline rule — " +
                "a false decline latch would kill the accepted offer (#594 commit precedence)",
            "uber.click.decline_offer",
            match?.ruleId,
        )
        assertNotEquals(
            "the accept CardView tap must never carry the decline intent",
            OfferIntent.DECLINE,
            match?.intent,
        )
    }

    @Test
    fun `the accept tap is structurally separable — non-leaf CardView with a labelled subtree`() {
        val node = load(acceptCardTap).node
        assertEquals("androidx.cardview.widget.CardView", node.className)
        assertTrue("the accept tap is NOT a leaf", node.children.isNotEmpty())
        assertTrue(
            "the accept tap's subtree carries the accept label",
            node.allText.any { it.equals("Match", ignoreCase = true) || it.equals("Accept", ignoreCase = true) },
        )
    }
}
