package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `nextSiblingMatchingRegex(...)` — the shape-anchored sibling read (#1029).
 *
 * `sibling(N)` addresses a slot by POSITION, which only holds while the row's shape does. DoorDash
 * 8.93.7 flattened the receipt breakdown into id-less siblings — `'Customer tips'`, `'799'`,
 * `'$7.00'` — where `'799'` is a type CODE that older builds render in the `pay_line_item_title`
 * slot. `sibling(1)` off the label therefore handed `parseCurrency` the string `799` and the parse
 * reported a **$799.00 tip on a $16.70 delivery**. This navigate states the shape it wants instead
 * of an offset, so the code node is skipped structurally.
 *
 * Bounded by [MAX_SIBLING_SCAN]; the pattern goes through [RegexSafety] at COMPILE time.
 */
class NextSiblingMatchingRegexTest {

    private val currencyNav = "nextSiblingMatchingRegex(^\\\$[\\d,]+\\.\\d{2}\$)"

    private fun rules(nav: String, findText: String = "Customer tips") = """[{
        "id": "doordash.screen.sibling_scan",
        "priority": 1,
        "require": { "exists": { "hasNoId": true } },
        "parse": {
            "as": "idle",
            "fields": {
                "value": {
                    "find": { "hasText": "$findText" },
                    "navigate": "${nav.replace("\\", "\\\\")}",
                    "read": "text",
                    "transform": "parseCurrency"
                }
            }
        }
    }]"""

    private fun rulesetFor(nav: String, findText: String = "Customer tips") =
        Ruleset(
            RuleCompiler.compileRules<UiNode>(
                Json.parseToJsonElement(rules(nav, findText)).jsonArray,
                RuleContext.SCREEN,
            ),
        )

    private fun row(vararg texts: String?): UiNode =
        UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

    private fun valueOf(tree: UiNode, nav: String = currencyNav, findText: String = "Customer tips"): Any? =
        rulesetFor(nav, findText).matchFirst(tree, "doordash")?.fields?.get("value")

    // =========================================================================
    // The $799 fabrication
    // =========================================================================

    @Test
    fun `the scan skips the stray type code and reads the real tip`() {
        // The 8.93.7 breakdown row, verbatim.
        val tree = row(
            "DoorDash pay", "Base pay", "\$8.70", "Peak pay", "\$1.00",
            "Customer tips", "799", "\$7.00",
        )
        assertEquals(7.00, valueOf(tree))
    }

    @Test
    fun `the positional read this replaces is what produced the 799`() {
        // Characterization of the bug, so the regression is impossible to reintroduce silently.
        val tree = row("Customer tips", "799", "\$7.00")
        assertEquals(799.0, valueOf(tree, nav = "sibling(1)"))
        assertEquals(7.00, valueOf(tree))
    }

    @Test
    fun `a directly-adjacent value still reads`() {
        assertEquals(7.00, valueOf(row("Customer tips", "\$7.00")))
    }

    @Test
    fun `a text-less sibling in the way is stepped over`() {
        // The receipt's Collapse/Expand affordance carries a contentDescription, no text.
        val tree = UiNode(
            children = listOf(
                UiNode(text = "This offer"),
                UiNode(contentDescription = "Collapse"),
                UiNode(text = "\$16.70"),
            ),
        ).restoreParents()
        assertEquals(16.70, valueOf(tree, findText = "This offer"))
    }

    // =========================================================================
    // Fail-null, and the bound
    // =========================================================================

    @Test
    fun `no matching sibling reads null rather than the nearest node`() {
        assertNull(valueOf(row("Customer tips", "799", "Peak pay")))
    }

    @Test
    fun `a label with no siblings at all reads null`() {
        // The pre-8.93.7 nesting: the label is the sole child of its own container.
        val tree = UiNode(
            children = listOf(UiNode(children = listOf(UiNode(text = "Customer tips")))),
        ).restoreParents()
        assertNull(valueOf(tree))
    }

    @Test
    fun `the scan stops at the cap`() {
        val fillers = Array(MAX_SIBLING_SCAN) { "filler" }
        // Value sits at offset MAX_SIBLING_SCAN — the last slot the scan looks at.
        val inRange = row("Customer tips", *fillers.dropLast(1).toTypedArray(), "\$7.00")
        assertEquals(7.00, valueOf(inRange))
        // One slot further out: past the bound, so it must NOT be found.
        val outOfRange = row("Customer tips", *fillers, "\$7.00")
        assertNull(valueOf(outOfRange))
    }

    // =========================================================================
    // The pattern is untrusted input — it is guarded at LOAD time
    // =========================================================================

    private fun compileFailure(nav: String): String {
        val error = try {
            RuleCompiler.compileRules<UiNode>(
                Json.parseToJsonElement(rules(nav)).jsonArray,
                RuleContext.SCREEN,
            )
            null
        } catch (e: RuleCompileException) {
            e
        }
        assertTrue(
            "'$nav' must fail the rule LOAD — a rule-authored pattern is untrusted input and " +
                "never reaches a live match unguarded",
            error != null,
        )
        return error!!.message.orEmpty()
    }

    @Test
    fun `a ReDoS-prone pattern is rejected at compile`() {
        // Untagged (non-isolable) by design: RegexSafety is a security control, so it rejects the
        // whole FILE rather than silently degrading one surface.
        assertTrue(
            "the rejection must name the ReDoS guard",
            compileFailure("nextSiblingMatchingRegex((a+)+b)").contains("unbounded"),
        )
    }

    @Test
    fun `an unparseable pattern is rejected at compile`() {
        assertTrue(
            "the rejection must name the invalid pattern",
            compileFailure("nextSiblingMatchingRegex([unclosed)").contains("Invalid regex"),
        )
    }

    @Test
    fun `an over-long pattern is rejected at compile`() {
        assertTrue(
            "the length cap must apply to this pattern like every other rule-authored one",
            compileFailure("nextSiblingMatchingRegex(${"a".repeat(RuleCompiler.MAX_REGEX_LENGTH + 1)})")
                .contains("MAX_REGEX_LENGTH"),
        )
    }
}
