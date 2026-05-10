package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Ruleset.matchFirst] (screen rules).
 *
 * Rules and branches are constructed directly from [CompiledRule] / [CompiledBranch]
 * data classes — no JSON parsing involved.
 */
class ScreenRulesetTest {

    private fun node(text: String? = null, viewId: String? = null) =
        UiNode(text = text, viewIdResourceName = viewId)

    private fun branch(
        intent: String,
        guards: List<(UiNode) -> Boolean> = emptyList(),
        condition: (UiNode) -> Boolean,
    ) = CompiledBranch<UiNode>(
        intent = intent,
        rejectChecks = guards,
        predicate = condition,
    )

    private fun rule(
        id: String,
        priority: Int,
        vararg branches: CompiledBranch<UiNode>,
    ) = CompiledRule(
        id = id,
        priority = priority,
        overrideable = true,
        branches = branches.toList(),
    )

    /** Extract intent string from RuleMatchResult for concise assertions. */
    private fun Ruleset<UiNode>.matchIntent(tree: UiNode): String? = matchFirst(tree)?.intent

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `matchFirst returns null when no rules match`() {
        val ruleset = Ruleset(
            listOf(rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }))
        )
        assertNull(ruleset.matchIntent(node(text = "Something else")))
    }

    @Test
    fun `matchFirst returns the intent of the matching branch`() {
        val ruleset = Ruleset(
            listOf(rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }))
        )
        assertEquals("OFFER_POPUP", ruleset.matchIntent(node(text = "Offer")))
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `matchFirst evaluates rules in ascending priority order`() {
        val ruleset = Ruleset(
            listOf(
                // Priority 20 — would match IDLE_MAP
                rule("low-priority", 20, branch("MAIN_MAP_IDLE") { true }),
                // Priority 10 — evaluated first; matches OFFER
                rule("high-priority", 10, branch("OFFER_POPUP") { true }),
            )
        )
        // Priority-10 rule wins even though it was added second
        assertEquals("OFFER_POPUP", ruleset.matchIntent(node()))
    }

    @Test
    fun `matchFirst skips non-matching rules and continues to next`() {
        val ruleset = Ruleset(
            listOf(
                rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }),
                rule("r2", 20, branch("MAIN_MAP_IDLE") { it.text == "Map" }),
            )
        )
        assertEquals("MAIN_MAP_IDLE", ruleset.matchIntent(node(text = "Map")))
    }

    // =========================================================================
    // Guard logic
    // =========================================================================

    @Test
    fun `guard fires — branch is skipped`() {
        val guardFires: (UiNode) -> Boolean = { true }   // always fires
        val ruleset = Ruleset(
            listOf(
                rule(
                    "r1", 10,
                    branch("OFFER_POPUP", guards = listOf(guardFires)) { true }
                )
            )
        )
        // Guard fires → branch is skipped → no match → null
        assertNull(ruleset.matchIntent(node()))
    }

    @Test
    fun `guard does not fire — branch is evaluated normally`() {
        val guardSilent: (UiNode) -> Boolean = { false }  // never fires
        val ruleset = Ruleset(
            listOf(
                rule(
                    "r1", 10,
                    branch("OFFER_POPUP", guards = listOf(guardSilent)) { true }
                )
            )
        )
        assertEquals("OFFER_POPUP", ruleset.matchIntent(node()))
    }

    @Test
    fun `any firing guard skips the branch even if condition is true`() {
        val g1: (UiNode) -> Boolean = { false }
        val g2: (UiNode) -> Boolean = { true }  // this one fires
        val ruleset = Ruleset(
            listOf(
                rule(
                    "r1", 10,
                    branch("OFFER_POPUP", guards = listOf(g1, g2)) { true }
                )
            )
        )
        assertNull(ruleset.matchIntent(node()))
    }

    // =========================================================================
    // Branches within a rule
    // =========================================================================

    @Test
    fun `first matching branch in a rule wins`() {
        val ruleset = Ruleset(
            listOf(
                rule(
                    "branched", 10,
                    branch("DELIVERY_SUMMARY_EXPANDED") { it.text == "expanded" },
                    branch("DELIVERY_SUMMARY_COLLAPSED") { it.text == "collapsed" },
                )
            )
        )
        assertEquals("DELIVERY_SUMMARY_EXPANDED", ruleset.matchIntent(node(text = "expanded")))
        assertEquals("DELIVERY_SUMMARY_COLLAPSED", ruleset.matchIntent(node(text = "collapsed")))
    }

    @Test
    fun `subsequent rule evaluated when earlier rule has no matching branch`() {
        val ruleset = Ruleset(
            listOf(
                rule("r1", 10, branch("OFFER_POPUP") { it.text == "Offer" }),
                rule("r2", 20, branch("MAIN_MAP_IDLE") { true }),
            )
        )
        // r1 doesn't match "Map", r2 matches everything
        assertEquals("MAIN_MAP_IDLE", ruleset.matchIntent(node(text = "Map")))
    }

    // =========================================================================
    // ruleCount
    // =========================================================================

    @Test
    fun `ruleCount reflects the number of compiled rules`() {
        val ruleset = Ruleset(
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
        assertNull(Ruleset<UiNode>(emptyList()).matchIntent(node()))
    }

    // =========================================================================
    // Template interpolation in effects
    // =========================================================================

    private fun branchWithEffects(
        intent: String,
        effects: List<CompiledEffect>,
        parser: (UiNode, Bindings) -> Map<String, Any?> = { _, _ -> emptyMap() },
    ) = CompiledBranch<UiNode>(
        intent = intent,
        predicate = { true },
        parser = parser,
        effects = effects,
    )

    @Test
    fun `effect args resolve template references against parsed fields`() {
        val effect = CompiledEffect(
            verb = EffectVerb.SCREENSHOT,
            args = mapOf("prefix" to "Offer - {storeName}"),
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "test.rule", priority = 10, overrideable = true,
                    branches = listOf(
                        branchWithEffects(
                            "OFFER",
                            listOf(effect),
                            parser = { _, _ -> mapOf("storeName" to "Chipotle") },
                        )
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals(1, result.effects.size)
        assertEquals("Offer - Chipotle", result.effects[0].args["prefix"])
    }

    @Test
    fun `dedupeKey resolves template references`() {
        val effect = CompiledEffect(
            verb = EffectVerb.LOG,
            args = mapOf("type" to "OFFER_RECEIVED"),
            dedupeKey = "offer-{offerHash}",
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "test.rule", priority = 10, overrideable = true,
                    branches = listOf(
                        branchWithEffects(
                            "OFFER",
                            listOf(effect),
                            parser = { _, _ -> mapOf("offerHash" to "abc123") },
                        )
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals("offer-abc123", result.effects[0].dedupeKey)
    }

    @Test
    fun `unknown template fields are left as-is`() {
        val effect = CompiledEffect(
            verb = EffectVerb.BUBBLE,
            args = mapOf("text" to "Value: {unknown}"),
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "test.rule", priority = 10, overrideable = true,
                    branches = listOf(
                        branchWithEffects("X", listOf(effect)),
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals("Value: {unknown}", result.effects[0].args["text"])
    }

    @Test
    fun `template values are sanitized — control chars stripped`() {
        val effect = CompiledEffect(
            verb = EffectVerb.BUBBLE,
            args = mapOf("text" to "Hi {name}"),
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "test.rule", priority = 10, overrideable = true,
                    branches = listOf(
                        branchWithEffects(
                            "X",
                            listOf(effect),
                            parser = { _, _ -> mapOf("name" to "Bob\u0000\u0007") },
                        )
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals("Hi Bob", result.effects[0].args["text"])
    }

    @Test
    fun `template values are capped at max length`() {
        val longValue = "X".repeat(500)
        val effect = CompiledEffect(
            verb = EffectVerb.LOG,
            args = mapOf("payload" to "{data}"),
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "test.rule", priority = 10, overrideable = true,
                    branches = listOf(
                        branchWithEffects(
                            "X",
                            listOf(effect),
                            parser = { _, _ -> mapOf("data" to longValue) },
                        )
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals(Ruleset.MAX_TEMPLATE_VALUE_LENGTH, result.effects[0].args["payload"]!!.length)
    }

    @Test
    fun `args without template references pass through unchanged`() {
        val effect = CompiledEffect(
            verb = EffectVerb.SCREENSHOT,
            args = mapOf("prefix" to "Static Value"),
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "test.rule", priority = 10, overrideable = true,
                    branches = listOf(
                        branchWithEffects(
                            "X",
                            listOf(effect),
                            parser = { _, _ -> mapOf("storeName" to "Ignored") },
                        )
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals("Static Value", result.effects[0].args["prefix"])
    }

    @Test
    fun `multiple effects resolve independently`() {
        val effects = listOf(
            CompiledEffect(
                verb = EffectVerb.SCREENSHOT,
                args = mapOf("prefix" to "Offer - {storeName}"),
                dedupeKey = "ss-{offerHash}",
                throttleMs = 60000,
            ),
            CompiledEffect(
                verb = EffectVerb.LOG,
                args = mapOf("type" to "OFFER_RECEIVED", "payload" to "pay={payAmount}"),
                dedupeKey = "log-{offerHash}",
                throttleMs = 60000,
            ),
        )
        val ruleset = Ruleset(
            listOf(
                CompiledRule<UiNode>(
                    id = "doordash.screen.offer", priority = 20, overrideable = true,
                    branches = listOf(
                        branchWithEffects(
                            "OFFER_POPUP",
                            effects,
                            parser = { _, _ -> mapOf(
                                "storeName" to "Chipotle",
                                "offerHash" to "h1",
                                "payAmount" to 7.5,
                            ) },
                        )
                    ),
                )
            )
        )

        val result = ruleset.matchFirst(node())!!
        assertEquals(2, result.effects.size)

        val ssEffect = result.effects[0]
        assertEquals(EffectVerb.SCREENSHOT, ssEffect.verb)
        assertEquals("Offer - Chipotle", ssEffect.args["prefix"])
        assertEquals("ss-h1", ssEffect.dedupeKey)
        assertEquals(60000L, ssEffect.throttleMs)

        val logEffect = result.effects[1]
        assertEquals(EffectVerb.LOG, logEffect.verb)
        assertEquals("pay=7.5", logEffect.args["payload"])
        assertEquals("log-h1", logEffect.dedupeKey)
    }
}
