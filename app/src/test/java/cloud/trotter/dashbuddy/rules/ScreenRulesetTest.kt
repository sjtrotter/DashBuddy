package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [ScreenRuleset.matchFirst].
 *
 * Rules and branches are constructed directly from [CompiledScreenRule] / [CompiledBranch]
 * data classes — no JSON parsing involved.
 */
class ScreenRulesetTest {

    private fun node(text: String? = null, viewId: String? = null) =
        UiNode(text = text, viewIdResourceName = viewId)

    private fun branch(
        target: String,
        guards: List<(UiNode) -> Boolean> = emptyList(),
        condition: (UiNode) -> Boolean,
    ) = CompiledBranch(
        target = target,
        rejectChecks = guards.map { guard -> { tree: UiNode, _: Bindings -> guard(tree) } },
        requireCheck = { tree, _ -> condition(tree) },
    )

    private fun rule(
        id: String,
        priority: Int,
        vararg branches: CompiledBranch,
    ) = CompiledScreenRule(
        id = id,
        priority = priority,
        overrideable = true,
        branches = branches.toList(),
    )

    /** Extract target string from ScreenMatchResult for concise assertions. */
    private fun ScreenRuleset.matchTarget(tree: UiNode): String? = matchFirst(tree)?.target

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `matchFirst returns null when no rules match`() {
        val ruleset = ScreenRuleset(
            listOf(rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }))
        )
        assertNull(ruleset.matchTarget(node(text = "Something else")))
    }

    @Test
    fun `matchFirst returns the target of the matching branch`() {
        val ruleset = ScreenRuleset(
            listOf(rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }))
        )
        assertEquals("OFFER_POPUP", ruleset.matchTarget(node(text = "Offer")))
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `matchFirst evaluates rules in ascending priority order`() {
        val ruleset = ScreenRuleset(
            listOf(
                // Priority 20 — would match IDLE_MAP
                rule("low-priority", 20, branch("MAIN_MAP_IDLE") { true }),
                // Priority 10 — evaluated first; matches OFFER
                rule("high-priority", 10, branch("OFFER_POPUP") { true }),
            )
        )
        // Priority-10 rule wins even though it was added second
        assertEquals("OFFER_POPUP", ruleset.matchTarget(node()))
    }

    @Test
    fun `matchFirst skips non-matching rules and continues to next`() {
        val ruleset = ScreenRuleset(
            listOf(
                rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }),
                rule("r2", 20, branch("MAIN_MAP_IDLE") { it.text == "Map" }),
            )
        )
        assertEquals("MAIN_MAP_IDLE", ruleset.matchTarget(node(text = "Map")))
    }

    // =========================================================================
    // Guard logic
    // =========================================================================

    @Test
    fun `guard fires — branch is skipped`() {
        val guardFires: (UiNode) -> Boolean = { true }   // always fires
        val ruleset = ScreenRuleset(
            listOf(
                rule(
                    "r1", 10,
                    branch("OFFER_POPUP", guards = listOf(guardFires)) { true }
                )
            )
        )
        // Guard fires → branch is skipped → no match → null
        assertNull(ruleset.matchTarget(node()))
    }

    @Test
    fun `guard does not fire — branch is evaluated normally`() {
        val guardSilent: (UiNode) -> Boolean = { false }  // never fires
        val ruleset = ScreenRuleset(
            listOf(
                rule(
                    "r1", 10,
                    branch("OFFER_POPUP", guards = listOf(guardSilent)) { true }
                )
            )
        )
        assertEquals("OFFER_POPUP", ruleset.matchTarget(node()))
    }

    @Test
    fun `any firing guard skips the branch even if condition is true`() {
        val g1: (UiNode) -> Boolean = { false }
        val g2: (UiNode) -> Boolean = { true }  // this one fires
        val ruleset = ScreenRuleset(
            listOf(
                rule(
                    "r1", 10,
                    branch("OFFER_POPUP", guards = listOf(g1, g2)) { true }
                )
            )
        )
        assertNull(ruleset.matchTarget(node()))
    }

    // =========================================================================
    // Branches within a rule
    // =========================================================================

    @Test
    fun `first matching branch in a rule wins`() {
        val ruleset = ScreenRuleset(
            listOf(
                rule(
                    "branched", 10,
                    branch("DELIVERY_SUMMARY_EXPANDED") { it.text == "expanded" },
                    branch("DELIVERY_SUMMARY_COLLAPSED") { it.text == "collapsed" },
                )
            )
        )
        assertEquals("DELIVERY_SUMMARY_EXPANDED", ruleset.matchTarget(node(text = "expanded")))
        assertEquals("DELIVERY_SUMMARY_COLLAPSED", ruleset.matchTarget(node(text = "collapsed")))
    }

    @Test
    fun `subsequent rule evaluated when earlier rule has no matching branch`() {
        val ruleset = ScreenRuleset(
            listOf(
                rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }),
                rule("r2", 20, branch("MAIN_MAP_IDLE") { true }),
            )
        )
        // r1 doesn't match "Map", r2 matches everything
        assertEquals("MAIN_MAP_IDLE", ruleset.matchTarget(node(text = "Map")))
    }

    // =========================================================================
    // ruleCount
    // =========================================================================

    @Test
    fun `ruleCount reflects the number of compiled rules`() {
        val ruleset = ScreenRuleset(
            listOf(
                rule("r1", 10, branch("OFFER_POPUP") { true }),
                rule("r2", 20, branch("MAIN_MAP_IDLE") { true }),
                rule("r3", 30, branch("DASH_PAUSED") { true }),
            )
        )
        assertEquals(3, ruleset.ruleCount)
    }

    @Test
    fun `empty ruleset returns null`() {
        assertNull(ScreenRuleset(emptyList()).matchTarget(node()))
    }
}
