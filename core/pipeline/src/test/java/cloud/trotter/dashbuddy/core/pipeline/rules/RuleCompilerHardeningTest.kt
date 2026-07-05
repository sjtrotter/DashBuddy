package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #293 — the RuleCompiler robustness pack (the Phase-A2 survivors of #211). One
 * focused test per item: multi-key predicate reject (1), value-honoring boolean
 * flags (2), typed compile errors (3), per-rule fault isolation with the
 * opt-in `isolable` isolation + sensitive/untagged whole-file default (4 —
 * both arms + the inversion canary), known-key
 * validation (5), and rule-id platform-prefix validation (8). Locale.ROOT (6)
 * and the memoized joined text (9) are proven behavior-equivalent by the
 * existing predicate suites + goldens; item 7 (regex match-time timeout) is
 * already delivered by #680's BoundedRegex/RegexSafety.
 */
class RuleCompilerHardeningTest {

    private fun node(
        viewId: String? = null,
        text: String? = null,
        isClickable: Boolean = false,
    ) = UiNode(viewIdResourceName = viewId, text = text, isClickable = isClickable)

    private fun arr(s: String) = Json.parseToJsonElement(s).jsonArray

    private fun compile(s: String) =
        RuleCompiler.compileRules<UiNode>(arr(s), RuleContext.SCREEN)

    private fun compileWithPlatform(s: String, platformId: String) =
        RuleCompiler.compileRules<UiNode>(arr(s), RuleContext.SCREEN, platformId)

    private fun expectCompileFails(block: () -> Unit, contains: String? = null) {
        try {
            block()
            fail("expected RuleCompileException")
        } catch (e: RuleCompileException) {
            contains?.let {
                assertTrue("message '${e.message}' should contain '$it'", e.message?.contains(it) == true)
            }
        }
    }

    // ── Item 1: multi-key predicate objects reject ──────────────────────────

    @Test
    fun `a node predicate with two keys is rejected (was silently first-key-only)`() {
        expectCompileFails(
            { RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "hasText": "x", "hasIdSuffix": "y" }""")) },
            contains = "exactly one key",
        )
    }

    @Test
    fun `a tree predicate with two keys is rejected`() {
        expectCompileFails(
            { RuleCompiler.compileTreePred(Json.parseToJsonElement("""{ "exists": { "hasText": "x" }, "not": { "hasText": "y" } }""")) },
            contains = "exactly one key",
        )
    }

    @Test
    fun `a notification predicate with two keys is rejected`() {
        expectCompileFails(
            { RuleCompiler.compileNotifPred(Json.parseToJsonElement("""{ "titleEquals": "x", "textEquals": "y" }""")) },
            contains = "exactly one key",
        )
    }

    @Test
    fun `a single-key predicate still compiles`() {
        val pred = RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "hasText": "Accept" }"""))
        assertTrue(pred(node(text = "Accept")))
    }

    // ── Item 2: boolean-flag predicates honor their value ───────────────────

    @Test
    fun `isClickable false matches NON-clickable nodes`() {
        val wantFalse = RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "isClickable": false }"""))
        assertTrue("isClickable:false must match a non-clickable node", wantFalse(node(isClickable = false)))
        assertFalse("isClickable:false must NOT match a clickable node", wantFalse(node(isClickable = true)))

        val wantTrue = RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "isClickable": true }"""))
        assertTrue(wantTrue(node(isClickable = true)))
        assertFalse(wantTrue(node(isClickable = false)))
    }

    @Test
    fun `hasNoId false matches nodes that HAVE an id`() {
        val wantFalse = RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "hasNoId": false }"""))
        assertTrue(wantFalse(node(viewId = "com.x:id/btn")))
        assertFalse(wantFalse(node(viewId = null)))
    }

    @Test
    fun `a non-boolean value for a boolean flag is a typed compile error`() {
        expectCompileFails(
            { RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "isClickable": "yes" }""")) },
            contains = "boolean",
        )
    }

    // ── Item 3: typed compile errors (NPE/CCE never escape as themselves) ────

    @Test
    fun `a rule missing id fails with a typed RuleCompileException, not an NPE`() {
        expectCompileFails(
            { compile("""[{ "priority": 10, "require": { "exists": { "hasText": "x" } } }]""") },
            contains = "id",
        )
    }

    @Test
    fun `a rule with a non-integer priority fails typed, not a CCE`() {
        // Routed through a sensitive-layer id so the typed reject propagates out of
        // the per-rule isolation loop and its message is observable (a non-sensitive
        // equivalent would isolate to a skip — see the item-4 tests).
        expectCompileFails(
            { compile("""[{ "id": "doordash.screen.sensitive.x", "priority": "high", "require": { "exists": { "hasText": "x" } } }]""") },
            contains = "priority",
        )
    }

    @Test
    fun `a predicate scalar given an object fails typed, not a CCE`() {
        expectCompileFails(
            { RuleCompiler.compileNodePred(Json.parseToJsonElement("""{ "hasText": { "nested": true } }""")) },
            contains = "scalar",
        )
    }

    // ── Item 4: per-rule fault isolation — BOTH arms ────────────────────────

    @Test
    fun `a malformed non-sensitive rule skips while its valid siblings survive`() {
        val rules = compile(
            """
            [
              { "id": "doordash.screen.good_a", "priority": 10, "require": { "exists": { "hasText": "A" } } },
              { "id": "doordash.screen.bad", "priority": 11, "require": { "exists": { "hasText": "B" } }, "bogusKey": true },
              { "id": "doordash.screen.good_b", "priority": 12, "require": { "exists": { "hasText": "C" } } }
            ]
            """.trimIndent(),
        )
        assertEquals(
            "the bad rule is skipped; both good siblings survive",
            listOf("doordash.screen.good_a", "doordash.screen.good_b"),
            rules.map { it.id },
        )
    }

    @Test
    fun `a malformed SENSITIVE rule rejects the WHOLE file (never thin the pledge block)`() {
        expectCompileFails(
            {
                compile(
                    """
                    [
                      { "id": "doordash.screen.good", "priority": 10, "require": { "exists": { "hasText": "A" } } },
                      { "id": "doordash.screen.sensitive.banking", "priority": 11, "require": { "exists": { "hasText": "B" } }, "bogusKey": true }
                    ]
                    """.trimIndent(),
                )
            },
            contains = "bogusKey",
        )
    }

    @Test
    fun `a rule with an unreadable id rejects the WHOLE file (fail closed)`() {
        // No readable id → the isolation loop can't tell if it's sensitive → reject whole file.
        expectCompileFails(
            { compile("""[{ "priority": 10, "require": { "exists": { "hasText": "x" } } }]""") },
        )
    }

    @Test
    fun `a security reject (DoS cap) rejects the WHOLE file even for a non-sensitive rule`() {
        // MAX_EFFECTS_PER_RULE is an anti-DoS control. Isolation is opt-in
        // (review F1): security sites carry NO `isolable` tag, so the default
        // whole-file reject applies. Build a non-sensitive rule over the cap.
        val effects = (0..RuleCompiler.MAX_EFFECTS_PER_RULE).joinToString(",") {
            """{ "bubble": { "text": "e$it" } }"""
        }
        expectCompileFails(
            {
                compile(
                    """[{ "id": "doordash.screen.dos", "priority": 10, "require": { "exists": { "hasText": "x" } }, "effects": [ $effects ] }]""",
                )
            },
            contains = "MAX_EFFECTS_PER_RULE",
        )
    }

    @Test
    fun `CANARY - an UNTAGGED RuleCompileException rejects the WHOLE file (isolation is opt-in)`() {
        // The regression test for the review-F1 inversion itself: a bare
        // RuleCompileException (no `isolable` flag) thrown while compiling a
        // NON-sensitive rule must reject the whole file — the conservative
        // default arm. If this fails, either the isolation loop's default was
        // weakened (a Principle 6 violation — a future untagged security check
        // would silently downgrade to a per-rule skip), or the chosen untagged
        // site ("Empty tree predicate object") was tagged isolable — pick
        // another untagged site consciously, don't loosen the loop. The valid
        // sibling proves the file rejects WHOLE, not just the bad rule.
        expectCompileFails(
            {
                compile(
                    """
                    [
                      { "id": "doordash.screen.good", "priority": 10, "require": { "exists": { "hasText": "A" } } },
                      { "id": "doordash.screen.bare_error", "priority": 11, "require": {} }
                    ]
                    """.trimIndent(),
                )
            },
            contains = "Empty tree predicate",
        )
    }

    // ── Item 5: known-key validation at rule + branch level ─────────────────

    // (Rule/branch-level rejects are routed through a sensitive-layer id so the
    // typed message propagates out of the per-rule isolation loop and can be
    // asserted; the non-sensitive equivalent isolates to a skip — item 4.)

    @Test
    fun `the overridable typo is rejected at the rule level naming the key`() {
        expectCompileFails(
            {
                compile(
                    """[{ "id": "doordash.screen.sensitive.x", "priority": 10, "overridable": false, "require": { "exists": { "hasText": "x" } } }]""",
                )
            },
            contains = "overridable",
        )
    }

    @Test
    fun `an unknown branch key is rejected naming it`() {
        expectCompileFails(
            {
                compile(
                    """
                    [{
                      "id": "doordash.screen.sensitive.x", "priority": 10,
                      "branches": [
                        { "require": { "exists": { "hasText": "x" } }, "if_": true }
                      ]
                    }]
                    """.trimIndent(),
                )
            },
            contains = "if_",
        )
    }

    @Test
    fun `a legitimate metadata comment key is accepted`() {
        val rules = compile(
            """[{ "id": "doordash.screen.x", "priority": 10, "comment": "a note", "require": { "exists": { "hasText": "x" } } }]""",
        )
        assertEquals(1, rules.size)
    }

    // ── Item 8: rule-id platform prefix validated at load ───────────────────

    @Test
    fun `a rule id not carrying the file platform prefix is rejected`() {
        // Sensitive id so the reject propagates out of the isolation loop (a
        // mis-prefixed non-sensitive rule isolates to a skip — item 4).
        expectCompileFails(
            {
                compileWithPlatform(
                    """[{ "id": "uber.screen.sensitive.x", "priority": 10, "require": { "exists": { "hasText": "x" } } }]""",
                    platformId = "doordash.driver",
                )
            },
            contains = "platform prefix",
        )
    }

    @Test
    fun `a correctly-prefixed rule id passes the platform check`() {
        val rules = compileWithPlatform(
            """[{ "id": "doordash.screen.x", "priority": 10, "require": { "exists": { "hasText": "x" } } }]""",
            platformId = "doordash.driver",
        )
        assertEquals(1, rules.size)
    }
}
