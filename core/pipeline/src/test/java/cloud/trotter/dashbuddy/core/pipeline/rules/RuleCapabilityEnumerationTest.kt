package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #422 — per-rule actuating capabilities enumerated at load time, with the
 * consent key content-pinned to the binding DEFINITION (not the rule id or the
 * bind name) so a remote update that repoints a binding forces re-consent.
 */
class RuleCapabilityEnumerationTest {

    private fun compile(rulesJson: String): List<CompiledRule<UiNode>> =
        RuleCompiler.compileRules(Json.parseToJsonElement(rulesJson).jsonArray, RuleContext.SCREEN)

    /** A screen rule that auto-clicks a bound button on a recognized screen. */
    private fun autoClickRule(
        id: String = "doordash.offer.auto_decline",
        bindPredicate: String = """{ "hasText": "Decline" }""",
    ) = """
    [{
      "id": "$id",
      "priority": 10,
      "require": { "exists": { "hasText": "Decline" } },
      "bind": { "declineButton": { "find": $bindPredicate } },
      "effects": [ { "click": "${'$'}declineButton" } ]
    }]
    """.trimIndent()

    @Test
    fun `an auto-click rule yields exactly one CLICK capability, keyed and attributed to its source`() {
        val caps = RuleCompiler.enumerateCapabilities(compile(autoClickRule()), source = "asset:doordash.json")
        assertEquals(1, caps.size)
        val cap = caps.single()
        assertEquals("doordash.offer.auto_decline", cap.ruleId)
        assertEquals(EffectVerb.CLICK, cap.verb)
        assertEquals("declineButton", cap.targetBindName)
        assertEquals("asset:doordash.json", cap.source)
        assertTrue(cap.key.isNotBlank())
    }

    @Test
    fun `the same logical action is one capability and the key is stable across compiles`() {
        val a = RuleCompiler.enumerateCapabilities(compile(autoClickRule()), "asset:doordash.json").single()
        val b = RuleCompiler.enumerateCapabilities(compile(autoClickRule()), "asset:doordash.json").single()
        assertEquals(a.key, b.key)
    }

    @Test
    fun `repointing the binding predicate changes the key - silent escalation is caught`() {
        // Same rule id, same bind name, same click — but the binding now selects
        // the ACCEPT button. The grant must NOT carry over.
        val decline = RuleCompiler.enumerateCapabilities(
            compile(autoClickRule(bindPredicate = """{ "hasText": "Decline" }""")), "s",
        ).single()
        val accept = RuleCompiler.enumerateCapabilities(
            compile(autoClickRule(bindPredicate = """{ "hasText": "Accept" }""")), "s",
        ).single()
        assertNotEquals(decline.key, accept.key)
    }

    @Test
    fun `reordering binding keys does NOT change the key - no spurious re-consent`() {
        val one = RuleCompiler.enumerateCapabilities(
            compile(autoClickRule(bindPredicate = """{ "hasText": "Decline", "hasIdSuffix": "btn" }""")), "s",
        ).single()
        val reordered = RuleCompiler.enumerateCapabilities(
            compile(autoClickRule(bindPredicate = """{ "hasIdSuffix": "btn", "hasText": "Decline" }""")), "s",
        ).single()
        assertEquals(one.key, reordered.key)
    }

    @Test
    fun `the consent key is carried onto the emitted RequestedEffect for the runtime gate`() {
        val rules = compile(autoClickRule())
        val ruleset = Ruleset(rules)
        // Match the rule against a tree that binds the decline button.
        val tree = UiNode(children = listOf(UiNode(text = "Decline"))).restoreParents()
        val result = ruleset.matchFirst(tree, platformWire = "doordash")
        val clickEffect = result?.effects?.single { it.verb == EffectVerb.CLICK }
        val cap = RuleCompiler.enumerateCapabilities(rules, "s").single()
        assertEquals(
            "runtime effect carries the same key load-time enumeration produced",
            cap.key, clickEffect?.capabilityKey,
        )
    }

    @Test
    fun `non-actuating effects produce no capability and no key`() {
        val rules = compile(
            """
            [{
              "id": "doordash.offer.notify",
              "priority": 11,
              "require": { "exists": { "hasText": "Decline" } },
              "effects": [ { "bubble": { "text": "offer" } }, { "log": { "type": "OFFER_RECEIVED" } } ]
            }]
            """.trimIndent(),
        )
        assertTrue(RuleCompiler.enumerateCapabilities(rules, "s").isEmpty())
        val bubble = rules.single().branches.single().effects.first()
        assertNull(bubble.capabilityKey)
    }
}
