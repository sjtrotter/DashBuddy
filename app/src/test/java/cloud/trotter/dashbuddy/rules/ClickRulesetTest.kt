package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
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
        factory: (UiNode) -> ClickInfo,
    ) = CompiledClickRule(
        id = id, priority = priority, overrideable = true,
        condition = condition, factory = factory,
    )

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `classifyFirst returns null when no rules match`() {
        val ruleset = ClickRuleset(
            listOf(rule("r1", 10, { it.viewIdResourceName == "accept_button" }, { ClickInfo.AcceptOffer }))
        )
        assertNull(ruleset.classifyFirst(node(viewId = "decline_button")))
    }

    @Test
    fun `classifyFirst returns factory result for matching rule`() {
        val ruleset = ClickRuleset(
            listOf(rule("r1", 10, { it.viewIdResourceName == "accept_button" }, { ClickInfo.AcceptOffer }))
        )
        assertEquals(ClickInfo.AcceptOffer, ruleset.classifyFirst(node(viewId = "accept_button")))
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `first matching rule by priority wins`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("r2", 20, { true }, { ClickInfo.DeclineOffer }),
                rule("r1", 10, { true }, { ClickInfo.AcceptOffer }),
            )
        )
        // Priority 10 rule wins even though it was added second
        assertEquals(ClickInfo.AcceptOffer, ruleset.classifyFirst(node()))
    }

    @Test
    fun `non-matching rule is skipped and next rule is checked`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("r1", 10, { it.viewIdResourceName == "accept_button" }, { ClickInfo.AcceptOffer }),
                rule("r2", 20, { it.text == "Decline offer" }, { ClickInfo.DeclineOffer }),
            )
        )
        assertEquals(ClickInfo.DeclineOffer, ruleset.classifyFirst(node(text = "Decline offer")))
    }

    // =========================================================================
    // Factory receives the matched node
    // =========================================================================

    @Test
    fun `factory lambda receives the matched node`() {
        var capturedNode: UiNode? = null
        val ruleset = ClickRuleset(
            listOf(
                rule("r1", 10, { true }, { n ->
                    capturedNode = n
                    ClickInfo.AcceptOffer
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
                rule("r1", 10, { false }, { ClickInfo.AcceptOffer }),
                rule("r2", 20, { false }, { ClickInfo.DeclineOffer }),
            )
        )
        assertEquals(2, ruleset.ruleCount)
    }

    // =========================================================================
    // Realistic scenario: AcceptOffer → DeclineOffer → ArrivedAtStore priority
    // =========================================================================

    @Test
    fun `AcceptOffer rule fires before DeclineOffer when both conditions true`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("accept", 10,
                    { it.viewIdResourceName?.endsWith("accept_button") == true },
                    { ClickInfo.AcceptOffer }),
                rule("decline", 20,
                    { it.text?.equals("Decline offer", ignoreCase = true) == true },
                    { ClickInfo.DeclineOffer }),
            )
        )
        // Node has accept_button ID AND "Decline offer" text — AcceptOffer wins on priority
        assertEquals(
            ClickInfo.AcceptOffer,
            ruleset.classifyFirst(node(viewId = "com.example:id/accept_button", text = "Decline offer"))
        )
    }

    @Test
    fun `Unknown click — no rule matches — returns null`() {
        val ruleset = ClickRuleset(
            listOf(
                rule("accept", 10,
                    { it.viewIdResourceName?.endsWith("accept_button") == true },
                    { ClickInfo.AcceptOffer }),
            )
        )
        val result = ruleset.classifyFirst(node(viewId = "some_unknown_button", text = "Got it"))
        assertNull(result)
    }
}
