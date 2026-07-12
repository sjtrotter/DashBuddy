package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.action.TargetExpectation
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #734 — actuation target-binding disambiguation.
 *
 * Field logs (07-07/07-08) showed [UiInteractionHandler] resolving **2 verified
 * candidates** and falling back to "clicking first" for two RuleActions:
 * `CONFIRM_DECLINE` on the offer confirm-decline sheet and `EXPAND_EARNINGS` on
 * the collapsed delivery summary. "First in tree order" is luck, not
 * verification, and violates #425's fail-closed posture.
 *
 * This test drives the **real compiled ruleset** (matchers JSON5 → generated
 * assets → `TestRulesetFactory`) over the **committed corpus** and replays the
 * handler's own candidate-resolution pipeline (find-by-viewId candidate set →
 * label verification via the RuleAction's [TargetExpectation] → the real
 * [ClickCandidateRanker]) over the same node tree. It asserts the tightened
 * bindings resolve a single, decisive, *correct* target — never an
 * [ClickCandidateRanker.Tier.UNRESOLVED] tie among >1 survivor (the case the
 * paired handler change now aborts to manual).
 *
 * The replay mirrors the handler exactly:
 *  - candidate set = every node whose `viewIdResourceName` equals the pinned
 *    ref's `viewIdSuffix` (the handler's find-by-viewId strategy; corpus ids are
 *    already suffix-form). Falls back to by-text like the handler when the ref
 *    has no id.
 *  - labels = the node's own text/contentDescription + its bounded subtree's
 *    (depth 3, 24 nodes) — the same walk as `collectLabels`.
 *  - decision = `ClickCandidateRanker.rank`, then the handler's tie rule.
 */
class ActuationBindingResolutionTest {

    private data class Resolution(
        val verifiedCount: Int,
        val tier: ClickCandidateRanker.Tier,
        val resolved: UiNode?,
        val decisive: Boolean,
    )

    /** Mirror of `UiInteractionHandler.collectLabels` over a UiNode subtree. */
    private fun collectLabels(node: UiNode): List<String> {
        val labels = mutableListOf<String>()
        var visited = 0
        fun visit(n: UiNode, depth: Int) {
            if (depth > 3 || visited >= 24) return
            visited++
            n.text?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            for (c in n.children) visit(c, depth + 1)
        }
        visit(node, 0)
        return labels
    }

    /** Mirror of `UiInteractionHandler.findCandidates` (viewId → text) over a UiNode tree. */
    private fun findCandidates(tree: UiNode, ref: NodeRef): List<UiNode> {
        ref.viewIdSuffix?.takeIf { it.isNotEmpty() }?.let { id ->
            val byId = tree.findNodes { it.viewIdResourceName == id }
            if (byId.isNotEmpty()) return byId
        }
        ref.text?.takeIf { it.isNotEmpty() }?.let { txt ->
            val byText = tree.findNodes { (it.text?.contains(txt, ignoreCase = true) == true) }
            if (byText.isNotEmpty()) return byText
        }
        return emptyList()
    }

    /**
     * Replay the full handler resolution: candidate set → label verification →
     * [ClickCandidateRanker] → the handler's tie rule.
     */
    private fun resolve(tree: UiNode, ref: NodeRef, expectation: TargetExpectation): Resolution {
        val candidates = findCandidates(tree, ref)
        val verified = candidates.filter { expectation.matchesLabels(collectLabels(it)) }
        if (verified.isEmpty()) return Resolution(0, ClickCandidateRanker.Tier.UNRESOLVED, null, decisive = false)

        val facts = verified.map { node ->
            ClickCandidateRanker.CandidateFacts(
                text = node.text,
                labels = collectLabels(node),
                bounds = node.boundsInScreen,
            )
        }
        val ranked = ClickCandidateRanker.rank(ref, facts)
        // The handler's tie rule: an UNRESOLVED ranking among >1 survivor is the
        // ambiguous (now aborted) case; a single survivor, or a decisive tier, is fine.
        val decisive = !(ranked.tier == ClickCandidateRanker.Tier.UNRESOLVED && verified.size > 1)
        return Resolution(verified.size, ranked.tier, verified[ranked.index], decisive)
    }

    private fun matchTargets(tree: UiNode): Map<String, NodeRef> =
        TestRulesetFactory.screenRuleset.matchFirst(tree)?.targets ?: emptyMap()

    private fun subtreeHasText(node: UiNode, needle: String): Boolean {
        if (node.text?.equals(needle, ignoreCase = true) == true) return true
        return node.children.any { subtreeHasText(it, needle) }
    }

    // =========================================================================
    // CONFIRM_DECLINE — offer_popup_confirm_decline
    // =========================================================================

    @Test
    fun `confirm decline resolves exactly one verified button per corpus snapshot`() {
        val action = RuleAction.CONFIRM_DECLINE
        var withTarget = 0
        for ((filename, node, _) in TestResourceLoader.loadSnapshots("snapshots/offer_popup_confirm_decline")) {
            val ref = matchTargets(node)[action.targetBindName] ?: continue // optional bind
            withTarget++
            val r = resolve(node, ref, action.verification)
            // Exactly ONE node verifies (the confirm sheet's 'Decline offer' button); the
            // dialog-body 'Are you sure you want to decline...' copy has no id so it is
            // never in the find-by-viewId candidate set.
            assertEquals("$filename: exactly one candidate must verify for CONFIRM_DECLINE", 1, r.verifiedCount)
            assertTrue("$filename: resolution must be decisive (no UNRESOLVED tie)", r.decisive)
            assertEquals("$filename: resolved target must be the 'Decline offer' button", "Decline offer", r.resolved?.text)
        }
        assertTrue("expected the confirm-decline corpus to bind confirmDeclineButton on some frames", withTarget >= 4)
    }

    /**
     * The field scenario the corpus alone can't show (the corpus captured only the
     * sheet's own button): the offer popup BEHIND the confirm sheet carries its own
     * bare "Decline" title under the SAME `textView_prism_button_title` id. Under the
     * old `hasTextContaining: "decline"` bind, `findNode`'s pre-order walk could pin the
     * *first* such node (the offer-popup "Decline"), and both survive the `\bdecline\b`
     * label check — a genuine 2-candidate ambiguity. The exact-text anchor pins the
     * confirm sheet's "Decline offer", and the ranker's EXACT_TEXT tier then resolves
     * the single correct node decisively.
     */
    @Test
    fun `confirm decline binds the sheet button, not the offer-popup decline behind it`() {
        val action = RuleAction.CONFIRM_DECLINE
        // offer-popup "Decline" appears FIRST in pre-order; confirm sheet "Decline offer" second.
        val tree = UiNode(
            children = listOf(
                UiNode(text = "Are you sure you want to decline this offer?"),
                UiNode( // offer popup (behind the sheet)
                    viewIdResourceName = "secondary_action_button_dash_plus",
                    boundsInScreen = BoundingBox(40, 1600, 1000, 1720),
                    children = listOf(
                        UiNode(
                            viewIdResourceName = "textView_prism_button_title",
                            text = "Decline",
                            boundsInScreen = BoundingBox(40, 1600, 1000, 1720),
                        ),
                    ),
                ),
                UiNode( // confirm sheet button
                    boundsInScreen = BoundingBox(40, 2000, 1000, 2120),
                    children = listOf(
                        UiNode(
                            viewIdResourceName = "textView_prism_button_title",
                            text = "Decline offer",
                            boundsInScreen = BoundingBox(40, 2000, 1000, 2120),
                        ),
                    ),
                ),
            ),
        ).restoreParents()

        val ref = matchTargets(tree)[action.targetBindName]
        assertNotNull("confirm rule must bind confirmDeclineButton on the synthetic sheet", ref)
        assertEquals("bound target must be the sheet's 'Decline offer' node", "Decline offer", ref!!.text)

        val r = resolve(tree, ref, action.verification)
        assertEquals("both prism titles match \\bdecline\\b, so 2 nodes verify", 2, r.verifiedCount)
        assertEquals("EXACT_TEXT must break the tie decisively", ClickCandidateRanker.Tier.EXACT_TEXT, r.tier)
        assertTrue("resolution must be decisive", r.decisive)
        assertEquals("must resolve the confirm sheet's button", "Decline offer", r.resolved?.text)
        assertNotEquals("must NOT resolve the offer-popup bare 'Decline'", "Decline", r.resolved?.text)
    }

    // =========================================================================
    // EXPAND_EARNINGS — delivery_summary_collapsed
    // =========================================================================

    @Test
    fun `expand earnings binds the pay-breakdown expandable, never the stats one`() {
        val action = RuleAction.EXPAND_EARNINGS
        var withTarget = 0
        var decisiveFrames = 0
        for ((filename, node, _) in TestResourceLoader.loadSnapshots("snapshots/delivery_summary_collapsed")) {
            val ref = matchTargets(node)[action.targetBindName] ?: continue // optional bind
            withTarget++

            // The independently-computed expected target: the sole expandable_view whose
            // subtree does NOT carry the stats section's stable 'Total online time' row.
            val payNode = node.findNodes {
                it.viewIdResourceName == "expandable_view" && !subtreeHasText(it, "Total online time")
            }.singleOrNull()
            assertNotNull("$filename: exactly one pay-section expandable_view must exist", payNode)

            // The tightened bind must select that pay node (was: first-in-tree stats node).
            assertEquals(
                "$filename: bound expandButton must be the pay ('This offer') expandable, not the stats one",
                payNode!!.boundsInScreen, ref.boundsInScreen,
            )

            // EXPAND_EARNINGS is label-free, so both expandable_view nodes survive label
            // verification (the shared id can't be narrowed). The ranker must therefore
            // EITHER resolve the correct (pay) node via bounds, OR — on a degenerate frame
            // whose pay rect is collapsed/zero-area — return an UNRESOLVED tie, which the
            // handler now ABORTS to manual (#734). It must NEVER decisively resolve the
            // stats node (a wrong click).
            val r = resolve(node, ref, action.verification)
            if (r.decisive) {
                decisiveFrames++
                val resolved = r.resolved
                assertTrue(
                    "$filename: a decisive resolution must be the pay section (no 'Total online time')",
                    resolved != null && !subtreeHasText(resolved, "Total online time"),
                )
            }
        }
        assertTrue("expected the collapsed-summary corpus to bind expandButton on some frames", withTarget >= 5)
        assertTrue("expected the pay expandable to resolve decisively on non-degenerate frames", decisiveFrames >= 4)
    }
}
