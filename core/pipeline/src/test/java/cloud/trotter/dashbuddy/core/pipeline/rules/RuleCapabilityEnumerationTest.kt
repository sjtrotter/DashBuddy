package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #422/#425 — capabilities are enumerated at load time from the rules'
 * *target bindings* (well-known names from the app-owned [RuleAction]
 * registry), with the consent key content-pinned to the binding DEFINITION
 * (not the rule id or the bind name) so a remote update that repoints a
 * binding forces re-consent. Rules cannot declare actuation at all — a
 * `click` effect is a compile error.
 */
class RuleCapabilityEnumerationTest {

    private fun compile(rulesJson: String): List<CompiledRule<UiNode>> =
        RuleCompiler.compileRules(Json.parseToJsonElement(rulesJson).jsonArray, RuleContext.SCREEN)

    /** A screen rule that binds the well-known decline target. */
    private fun declineTargetRule(
        id: String = "doordash.screen.offer_popup_test",
        bindPredicate: String = """{ "hasText": "Decline" }""",
    ) = """
    [{
      "id": "$id",
      "priority": 10,
      "require": { "exists": { "hasText": "Decline" } },
      "bind": { "declineButton": { "find": $bindPredicate } }
    }]
    """.trimIndent()

    @Test
    fun `binding a well-known target yields exactly one capability, keyed and attributed to its source`() {
        val caps = RuleCompiler.enumerateCapabilities(compile(declineTargetRule()), source = "asset:doordash.json")
        assertEquals(1, caps.size)
        val cap = caps.single()
        assertEquals("doordash.screen.offer_popup_test", cap.ruleId)
        assertEquals(RuleAction.DECLINE_OFFER, cap.action)
        assertEquals("declineButton", cap.targetBindName)
        assertEquals("asset:doordash.json", cap.source)
        assertTrue(cap.key.isNotBlank())
    }

    @Test
    fun `the same logical binding is one capability and the key is stable across compiles`() {
        val a = RuleCompiler.enumerateCapabilities(compile(declineTargetRule()), "asset:doordash.json").single()
        val b = RuleCompiler.enumerateCapabilities(compile(declineTargetRule()), "asset:doordash.json").single()
        assertEquals(a.key, b.key)
    }

    @Test
    fun `repointing the binding predicate changes the key - silent escalation is caught`() {
        // Same rule id, same bind name — but the binding now selects the
        // ACCEPT button. The grant must NOT carry over.
        val decline = RuleCompiler.enumerateCapabilities(
            compile(declineTargetRule(bindPredicate = """{ "hasText": "Decline" }""")), "s",
        ).single()
        val accept = RuleCompiler.enumerateCapabilities(
            compile(declineTargetRule(bindPredicate = """{ "hasText": "Accept" }""")), "s",
        ).single()
        assertNotEquals(decline.key, accept.key)
    }

    @Test
    fun `reordering binding keys does NOT change the key - no spurious re-consent`() {
        // The consent key canonicalizes (recursively sorts) the binding
        // definition JSON, so reordering the bind ENTRY's keys must not change
        // it. A multi-key PREDICATE is now rejected by the compiler (#293 item 1
        // — extra predicate keys used to be silently dropped), so the reorder is
        // exercised on the entry's own keys (find + optional), which is the real
        // canonicalization surface.
        fun rule(entry: String) = """
        [{
          "id": "doordash.screen.offer_popup_test",
          "priority": 10,
          "require": { "exists": { "hasText": "Decline" } },
          "bind": { "declineButton": $entry }
        }]
        """.trimIndent()
        val one = RuleCompiler.enumerateCapabilities(
            compile(rule("""{ "find": { "hasText": "Decline" }, "optional": false }""")), "s",
        ).single()
        val reordered = RuleCompiler.enumerateCapabilities(
            compile(rule("""{ "optional": false, "find": { "hasText": "Decline" } }""")), "s",
        ).single()
        assertEquals(one.key, reordered.key)
    }

    @Test
    fun `a rule binding several well-known targets yields one capability per action`() {
        val caps = RuleCompiler.enumerateCapabilities(
            compile(
                """
                [{
                  "id": "doordash.screen.offer_popup_test",
                  "priority": 10,
                  "require": { "exists": { "hasText": "Decline" } },
                  "bind": {
                    "acceptButton": { "find": { "hasIdSuffix": "accept_button" }, "optional": true },
                    "declineButton": { "find": { "hasText": "Decline" }, "optional": true }
                  }
                }]
                """.trimIndent(),
            ),
            "s",
        )
        assertEquals(
            setOf(RuleAction.ACCEPT_OFFER, RuleAction.DECLINE_OFFER),
            caps.map { it.action }.toSet(),
        )
    }

    @Test
    fun `bindings with non-action names produce no capability`() {
        val rules = compile(
            """
            [{
              "id": "doordash.screen.summary_test",
              "priority": 11,
              "require": { "exists": { "hasText": "Decline" } },
              "bind": { "payField": { "find": { "hasTextContaining": "$" } } },
              "effects": [ { "bubble": { "text": "offer" } }, { "log": { "type": "OFFER_RECEIVED" } } ]
            }]
            """.trimIndent(),
        )
        assertTrue(RuleCompiler.enumerateCapabilities(rules, "s").isEmpty())
    }

    @Test
    fun `rule-declared click effects are rejected at compile time with a migration pointer`() {
        try {
            compile(
                """
                [{
                  "id": "doordash.offer.auto_decline",
                  "priority": 10,
                  "require": { "exists": { "hasText": "Decline" } },
                  "bind": { "declineButton": { "find": { "hasText": "Decline" } } },
                  "effects": [ { "click": "${'$'}declineButton" } ]
                }]
                """.trimIndent(),
            )
            fail("expected RuleCompileException for rule-declared click")
        } catch (e: RuleCompileException) {
            assertTrue(
                "error should point at the #425 migration: ${e.message}",
                e.message!!.contains("#425") && e.message!!.contains("bind"),
            )
        }
    }

    @Test
    fun `matching a rule exposes its resolved bindings as named targets`() {
        val rules = compile(declineTargetRule())
        val ruleset = Ruleset(rules)
        val tree = UiNode(children = listOf(UiNode(text = "Decline"))).restoreParents()
        val result = ruleset.matchFirst(tree, platformWire = "doordash")
        val target = result?.targets?.get("declineButton")
        assertEquals("Decline", target?.text)
    }
}
