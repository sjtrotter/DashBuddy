package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused unit test for the extracted [CapabilityEnumerator] (audit #11). The
 * end-to-end behaviour through the compiler is covered by
 * `RuleCapabilityEnumerationTest` (via `RuleCompiler.enumerateCapabilities`);
 * this hits the extracted object directly so the security-load-bearing
 * consent-key derivation has its own home — the content-pinned key and the
 * fail-closed dedup invariants are asserted in isolation.
 */
class CapabilityEnumeratorTest {

    private fun compile(rulesJson: String): List<CompiledRule<UiNode>> =
        RuleCompiler.compileRules(Json.parseToJsonElement(rulesJson).jsonArray, RuleContext.SCREEN)

    private fun declineRule(bindPredicate: String = """{ "hasText": "Decline" }""") = """
    [{
      "id": "doordash.screen.offer_popup_test",
      "priority": 10,
      "require": { "exists": { "hasText": "Decline" } },
      "enables": ["decline_offer"],
      "bind": { "declineButton": { "find": $bindPredicate } }
    }]
    """.trimIndent()

    @Test
    fun `binding a well-known target yields one keyed capability attributed to its source`() {
        val caps = CapabilityEnumerator.enumerate(compile(declineRule()), source = "asset:doordash.json")
        assertEquals(1, caps.size)
        val cap = caps.single()
        assertEquals("doordash.screen.offer_popup_test", cap.ruleId)
        assertEquals(RuleAction.DECLINE_OFFER, cap.action)
        assertEquals("declineButton", cap.targetBindName)
        assertEquals("asset:doordash.json", cap.source)
        assertTrue("key must be a non-blank hash", cap.key.isNotBlank())
    }

    @Test
    fun `the consent key is stable across compiles`() {
        val a = CapabilityEnumerator.enumerate(compile(declineRule()), "asset:doordash.json").single()
        val b = CapabilityEnumerator.enumerate(compile(declineRule()), "asset:doordash.json").single()
        assertEquals(a.key, b.key)
    }

    @Test
    fun `repointing the binding predicate changes the key - silent escalation is caught`() {
        val decline = CapabilityEnumerator.enumerate(
            compile(declineRule(bindPredicate = """{ "hasText": "Decline" }""")), "s",
        ).single()
        val accept = CapabilityEnumerator.enumerate(
            compile(declineRule(bindPredicate = """{ "hasText": "Accept" }""")), "s",
        ).single()
        assertNotEquals(decline.key, accept.key)
    }

    @Test
    fun `reordering binding keys does NOT change the consent key`() {
        // canonicalJson recursively sorts object keys, so reordering the bind
        // ENTRY's keys must not change the consent key. A multi-key PREDICATE is
        // now rejected by the compiler (#293 item 1), so the reorder is exercised
        // on the entry's own keys (find + optional).
        fun rule(entry: String) = """
        [{
          "id": "doordash.screen.offer_popup_test",
          "priority": 10,
          "require": { "exists": { "hasText": "Decline" } },
          "enables": ["decline_offer"],
          "bind": { "declineButton": $entry }
        }]
        """.trimIndent()
        val one = CapabilityEnumerator.enumerate(
            compile(rule("""{ "find": { "hasText": "Decline" }, "optional": false }""")), "s",
        ).single()
        val reordered = CapabilityEnumerator.enumerate(
            compile(rule("""{ "optional": false, "find": { "hasText": "Decline" } }""")), "s",
        ).single()
        assertEquals(one.key, reordered.key)
    }

    @Test
    fun `non-action bind names produce no capability`() {
        val rules = compile(
            """
            [{
              "id": "doordash.screen.summary_test",
              "priority": 11,
              "require": { "exists": { "hasText": "Decline" } },
              "bind": { "payField": { "find": { "hasTextContaining": "$" } } }
            }]
            """.trimIndent(),
        )
        assertTrue(CapabilityEnumerator.enumerate(rules, "s").isEmpty())
    }

    private fun branchedDeclineRule(vararg labels: String) = """
    [{
      "id": "doordash.screen.offer_popup_test",
      "priority": 10,
      "enables": ["decline_offer"],
      "branches": [${labels.joinToString { label ->
          """{
            "require": { "exists": { "hasText": "Offer" } },
            "bind": { "declineButton": { "find": { "hasText": "$label" } } }
          }"""
      }}]
    }]
    """.trimIndent()

    /** Distinct branch definitions share one consent decision, with both layouts disclosed (#1167). */
    @Test
    fun `a two-branch rule yields ONE capability per action with layoutCount 2`() {
        val caps = CapabilityEnumerator.enumerate(compile(branchedDeclineRule("Decline", "Decline offer")), "s")
        assertEquals(1, caps.size)
        assertEquals(RuleAction.DECLINE_OFFER, caps.single().action)
        assertEquals(2, caps.single().layoutCount)
    }

    /** Repointing any branch invalidates the grant for the entire action (#1167). */
    @Test
    fun `repointing one branch's predicate changes the key`() {
        val original = CapabilityEnumerator.enumerate(
            compile(branchedDeclineRule("Decline", "Decline offer")), "s",
        ).single()
        val repointed = CapabilityEnumerator.enumerate(
            compile(branchedDeclineRule("Decline", "Accept")), "s",
        ).single()
        assertNotEquals(original.key, repointed.key)
    }

    /** Branch ordering leaves the sorted definition set and consent key unchanged (#1167). */
    @Test
    fun `reordering branches does NOT change the key`() {
        val original = CapabilityEnumerator.enumerate(
            compile(branchedDeclineRule("Decline", "Decline offer")), "s",
        ).single()
        val reordered = CapabilityEnumerator.enumerate(
            compile(branchedDeclineRule("Decline offer", "Decline")), "s",
        ).single()
        assertEquals(original.key, reordered.key)
    }

    /** A newly covered layout requires one fresh consent decision for the action (#1167). */
    @Test
    fun `adding a branch layout changes the key`() {
        val original = CapabilityEnumerator.enumerate(compile(branchedDeclineRule("Decline")), "s").single()
        val extended = CapabilityEnumerator.enumerate(
            compile(branchedDeclineRule("Decline", "Decline offer")), "s",
        ).single()
        assertNotEquals(original.key, extended.key)
    }

    /** Inherited copies of a rule-level definition count as a single layout (#1167). */
    @Test
    fun `a rule-level bind inherited by two branches is one layout`() {
        val rules = compile(
            """
            [{
              "id": "doordash.screen.offer_popup_test",
              "priority": 10,
              "enables": ["decline_offer"],
              "bind": { "declineButton": { "find": { "hasText": "Decline" } } },
              "branches": [
                { "require": { "exists": { "hasText": "Legacy" } } },
                { "require": { "exists": { "hasText": "Compose" } } }
              ]
            }]
            """.trimIndent(),
        )
        val inherited = CapabilityEnumerator.enumerate(rules, "s").single()
        val original = CapabilityEnumerator.enumerate(compile(declineRule()), "s").single()
        assertEquals(1, inherited.layoutCount)
        assertEquals(original.key, inherited.key)
    }
}
