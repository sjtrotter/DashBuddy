package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Click screen-context constraints. The generic dispatcher contract lives in [ScreenRulesetTest].
 */
class ClickRulesetTest {

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

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
                            screenIs = setOf("offer_popup"),
                        ),
                    ),
                ),
            )
        )
        // Matches when screenTarget matches
        assertEquals("accept_offer", ruleset.matchFirst(node(), screenTarget = "offer_popup")?.intent)
        // Doesn't match when screenTarget differs
        assertNull(ruleset.matchFirst(node(), screenTarget = "idle_map"))
        // ...and a click with NO screen context satisfies no constrained branch.
        assertNull(ruleset.matchFirst(node(), screenTarget = null))
    }

    /**
     * #1104 — a multi-target `screenIs` matches on EVERY target it enumerates and on nothing
     * else. The constraint is a set now because DoorDash's confirm-decline tap is dispatched
     * before its own sheet is admitted (the classifier still holds the previous screen), so the
     * rule has to accept both; what must NOT change is that it stays a gate.
     */
    @Test
    fun `a multi-target screenIs matches every listed target and nothing else (#1104)`() {
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "r1", priority = 10, overrideable = true,
                    branches = listOf(
                        CompiledBranch(
                            predicate = { true },
                            intent = "decline_offer",
                            screenIs = setOf("offer_popup_confirm_decline", "offer_popup"),
                        ),
                    ),
                ),
            )
        )
        assertEquals(
            "decline_offer",
            ruleset.matchFirst(node(), screenTarget = "offer_popup_confirm_decline")?.intent,
        )
        assertEquals(
            "decline_offer",
            ruleset.matchFirst(node(), screenTarget = "offer_popup")?.intent,
        )
        assertNull("an unlisted target is still rejected", ruleset.matchFirst(node(), screenTarget = "idle_map"))
        assertNull("no screen context is still rejected", ruleset.matchFirst(node(), screenTarget = null))
    }
}
