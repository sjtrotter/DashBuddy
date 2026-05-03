package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClickRuleset.classifyFirst].
 */
class ClickRulesetTest {

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun rule(
        id: String,
        priority: Int,
        condition: (UiNode) -> Boolean,
        intentFactory: (UiNode) -> String,
    ) = CompiledClickRule(
        id = id, priority = priority, overrideable = true,
        condition = condition, intentFactory = intentFactory,
    )

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `classifyFirst returns null when no rules match`() {
        val ruleset = ClickRuleset(
            listOf(rule("r1", 10, { it.viewIdResourceName == "accept_button" }, { "accept_offer" }))
        )
        assertNull(ruleset.classifyFirst(node(viewId = "decline_button")))
    }

    @Test
    fun `classifyFirst returns intent for matching rule`() {
        val ruleset = ClickRuleset(
            listOf(rule("r1", 10, { it.viewIdResourceName == "accept_button" }, { "accept_offer" }))
        )
        assertEquals("accept_offer", ruleset.classifyFirst(node(viewId = "accept_button"))?.intent)
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `first matching rule by priority wins`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("r2", 20, { true }, { "decline_offer" }),
                rule("r1", 10, { true }, { "accept_offer" }),
            )
        )
        // Priority 10 rule wins even though it was added second
        assertEquals("accept_offer", ruleset.classifyFirst(node())?.intent)
    }

    @Test
    fun `non-matching rule is skipped and next rule is checked`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("r1", 10, { it.viewIdResourceName == "accept_button" }, { "accept_offer" }),
                rule("r2", 20, { it.text == "Decline offer" }, { "decline_offer" }),
            )
        )
        assertEquals("decline_offer", ruleset.classifyFirst(node(text = "Decline offer"))?.intent)
    }

    // =========================================================================
    // Factory receives the matched node
    // =========================================================================

    @Test
    fun `intentFactory lambda receives the matched node`() {
        var capturedNode: UiNode? = null
        val ruleset = ClickRuleset(
            listOf(
                rule("r1", 10, { true }, { n ->
                    capturedNode = n
                    "accept_offer"
                })
            )
        )
        val input = node(viewId = "accept_button")
        ruleset.classifyFirst(input)
        assertEquals(input, capturedNode)
    }

    // =========================================================================
    // ruleCount and empty ruleset
    // =========================================================================

    @Test
    fun `empty ruleset returns null`() {
        assertNull(ClickRuleset(emptyList()).classifyFirst(node()))
    }

    @Test
    fun `ruleCount reflects number of compiled rules`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("r1", 10, { false }, { "accept_offer" }),
                rule("r2", 20, { false }, { "decline_offer" }),
            )
        )
        assertEquals(2, ruleset.ruleCount)
    }

    // =========================================================================
    // Realistic scenario: accept_offer → decline_offer priority
    // =========================================================================

    @Test
    fun `accept_offer rule fires before decline_offer when both conditions true`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("accept", 10,
                    { it.viewIdResourceName?.endsWith("accept_button") == true },
                    { "accept_offer" }),
                rule("decline", 20,
                    { it.text?.equals("Decline offer", ignoreCase = true) == true },
                    { "decline_offer" }),
            )
        )
        // Node has accept_button ID AND "Decline offer" text — accept_offer wins on priority
        assertEquals(
            "accept_offer",
            ruleset.classifyFirst(node(viewId = "com.example:id/accept_button", text = "Decline offer"))?.intent
        )
    }

    @Test
    fun `Unknown click — no rule matches — returns null`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("accept", 10,
                    { it.viewIdResourceName?.endsWith("accept_button") == true },
                    { "accept_offer" }),
            )
        )
        val result = ruleset.classifyFirst(node(viewId = "some_unknown_button", text = "Got it"))
        assertNull(result)
    }
}
