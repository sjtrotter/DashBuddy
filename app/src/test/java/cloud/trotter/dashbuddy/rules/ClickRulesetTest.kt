package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Ruleset.matchFirst] with click rules.
 */
class ClickRulesetTest {

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun rule(
        id: String,
        priority: Int,
        intent: String,
        condition: (UiNode) -> Boolean,
    ) = CompiledRule<UiNode>(
        id = id, priority = priority, overrideable = true,
        branches = listOf(
            CompiledBranch(predicate = condition, intent = intent),
        ),
    )

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `matchFirst returns null when no rules match`() {
        val ruleset = Ruleset(
            listOf(rule("r1", 10, "accept_offer") { it.viewIdResourceName == "accept_button" })
        )
        assertNull(ruleset.matchFirst(node(viewId = "decline_button")))
    }

    @Test
    fun `matchFirst returns intent for matching rule`() {
        val ruleset = Ruleset(
            listOf(rule("r1", 10, "accept_offer") { it.viewIdResourceName == "accept_button" })
        )
        assertEquals("accept_offer", ruleset.matchFirst(node(viewId = "accept_button"))?.intent)
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `first matching rule by priority wins`() {
        val ruleset = Ruleset(
            listOf(
                rule("r2", 20, "decline_offer") { true },
                rule("r1", 10, "accept_offer") { true },
            )
        )
        // Priority 10 rule wins even though it was added second
        assertEquals("accept_offer", ruleset.matchFirst(node())?.intent)
    }

    @Test
    fun `non-matching rule is skipped and next rule is checked`() {
        val ruleset = Ruleset(
            listOf(
                rule("r1", 10, "accept_offer") { it.viewIdResourceName == "accept_button" },
                rule("r2", 20, "decline_offer") { it.text == "Decline offer" },
            )
        )
        assertEquals("decline_offer", ruleset.matchFirst(node(text = "Decline offer"))?.intent)
    }

    // =========================================================================
    // ruleCount and empty ruleset
    // =========================================================================

    @Test
    fun `empty ruleset returns null`() {
        assertNull(Ruleset<UiNode>(emptyList()).matchFirst(node()))
    }

    @Test
    fun `ruleCount reflects number of compiled rules`() {
        val ruleset = Ruleset(
            listOf(
                rule("r1", 10, "accept_offer") { false },
                rule("r2", 20, "decline_offer") { false },
            )
        )
        assertEquals(2, ruleset.ruleCount)
    }

    // =========================================================================
    // Realistic scenario: accept_offer → decline_offer priority
    // =========================================================================

    @Test
    fun `accept_offer rule fires before decline_offer when both conditions true`() {
        val ruleset = Ruleset(
            listOf(
                rule("accept", 10, "accept_offer") {
                    it.viewIdResourceName?.endsWith("accept_button") == true
                },
                rule("decline", 20, "decline_offer") {
                    it.text?.equals("Decline offer", ignoreCase = true) == true
                },
            )
        )
        // Node has accept_button ID AND "Decline offer" text — accept_offer wins on priority
        assertEquals(
            "accept_offer",
            ruleset.matchFirst(node(viewId = "com.example:id/accept_button", text = "Decline offer"))?.intent
        )
    }

    @Test
    fun `Unknown click — no rule matches — returns null`() {
        val ruleset = Ruleset(
            listOf(
                rule("accept", 10, "accept_offer") {
                    it.viewIdResourceName?.endsWith("accept_button") == true
                },
            )
        )
        val result = ruleset.matchFirst(node(viewId = "some_unknown_button", text = "Got it"))
        assertNull(result)
    }

    // =========================================================================
    // screenIs constraint
    // =========================================================================

    @Test
    fun `screenIs constraint filters by screen target`() {
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "r1", priority = 10, overrideable = true,
                    branches = listOf(
                        CompiledBranch(
                            predicate = { true },
                            intent = "accept_offer",
                            screenIs = "offer_popup",
                        ),
                    ),
                ),
            )
        )
        // Matches when screenTarget matches
        assertEquals("accept_offer", ruleset.matchFirst(node(), screenTarget = "offer_popup")?.intent)
        // Doesn't match when screenTarget differs
        assertNull(ruleset.matchFirst(node(), screenTarget = "idle_map"))
    }
}
