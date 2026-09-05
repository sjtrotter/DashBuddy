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

    /** The production shape, from its ONE owner — never a hand-copied pattern (#1029 E3). */
    private val currencyNav = "nextSiblingMatchingRegex(${CurrencyShape.RULE_PATTERN})"

    private fun rules(
        nav: String,
        findText: String = "Customer tips",
        find: String = """{ "hasText": "$findText" }""",
    ) = """[{
        "id": "doordash.screen.sibling_scan",
        "priority": 1,
        "require": { "exists": { "hasNoId": true } },
        "parse": {
            "as": "idle",
            "fields": {
                "value": {
                    "find": $find,
                    "navigate": "${nav.replace("\\", "\\\\")}",
                    "read": "text",
                    "transform": "parseCurrency"
                }
            }
        }
    }]"""

    private fun rulesetFor(
        nav: String,
        findText: String = "Customer tips",
        find: String = """{ "hasText": "$findText" }""",
    ) = Ruleset(
        RuleCompiler.compileRules<UiNode>(
            Json.parseToJsonElement(rules(nav, findText, find)).jsonArray,
            RuleContext.SCREEN,
        ),
    )

    private fun row(vararg texts: String?): UiNode =
        UiNode(children = texts.map { UiNode(text = it) }).restoreParents()

    private fun valueOf(
        tree: UiNode,
        nav: String = currencyNav,
        findText: String = "Customer tips",
        find: String = """{ "hasText": "$findText" }""",
    ): Any? = rulesetFor(nav, findText, find).matchFirst(tree, "doordash")?.fields?.get("value")

    /** The production form of the navigate: the shared shape plus this row's own width cap. */
    private fun cappedNav(cap: Int) = "nextSiblingMatchingRegex(${CurrencyShape.RULE_PATTERN}, $cap)"

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
    // The rule-declared scan cap (#1029 E1)
    // =========================================================================

    @Test
    fun `an uncapped scan walks out of the row and returns the NEXT row's money`() {
        // Characterization of what the cap exists for: when the tips VALUE is simply absent, the
        // unbounded scan hands back Peak pay's $1.00 AS the customer tip — fail-WRONG, and the
        // `sumApproxEquals` validator cannot catch it because appPay is null on 8.93.7.
        val tree = row("Customer tips", "799", "Peak pay", "\$1.00")
        assertEquals(1.00, valueOf(tree))
        assertNull("the cap stops the scan at this row's own width", valueOf(tree, nav = cappedNav(2)))
    }

    @Test
    fun `the capped scan still reads its own row, code node and all`() {
        assertEquals(7.00, valueOf(row("Customer tips", "799", "\$7.00"), nav = cappedNav(2)))
        assertEquals(7.00, valueOf(row("Customer tips", "\$7.00"), nav = cappedNav(2)))
    }

    @Test
    fun `an absent cap keeps the default`() {
        val fillers = Array(MAX_SIBLING_SCAN - 1) { "filler" }
        assertEquals(7.00, valueOf(row("Customer tips", *fillers, "\$7.00")))
    }

    @Test
    fun `a cap outside the bound isolates the rule at compile`() {
        // Tagged isolable (#293 item 4), like every other authoring-level navigate rejection: the
        // worst case of dropping one malformed non-sensitive rule is that surface degrading to
        // UNKNOWN, which is scrubbed. The sensitive-layer belt in `compileRules` still rejects the
        // whole file if such a rule were ever sensitive. Either way it never reaches a live match.
        for (bad in listOf(0, MAX_SIBLING_SCAN + 1, 99)) {
            val compiled = RuleCompiler.compileRules<UiNode>(
                Json.parseToJsonElement(rules(cappedNav(bad))).jsonArray,
                RuleContext.SCREEN,
            )
            assertTrue("cap $bad must not produce a live rule", compiled.isEmpty())
        }
    }

    @Test
    fun `an unparsable declared cap isolates the rule, it does not fall back to the default`() {
        // #1052: `toIntOrNull()` returns null on overflow, and the old `?: MAX_SIBLING_SCAN` folded
        // that into the DEFAULT — silently handing a rule the widest scan it can have while its
        // author had asked for something else entirely. A cap that is PRESENT is honoured or the
        // rule isolates; the default belongs only to a cap that was never written.
        for (bad in listOf("2147483648", "99999999999999999999")) {
            val compiled = RuleCompiler.compileRules<UiNode>(
                Json.parseToJsonElement(
                    rules("nextSiblingMatchingRegex(${CurrencyShape.RULE_PATTERN}, $bad)"),
                ).jsonArray,
                RuleContext.SCREEN,
            )
            assertTrue("cap $bad must not produce a live rule", compiled.isEmpty())
        }
    }

    @Test
    fun `a comma inside the pattern is not mistaken for a cap`() {
        // The split anchors on a trailing `, <digits>` at the END of the argument, and a shape
        // pattern ends in its own anchor. `[\d,]` and `{0,3}` are both left alone.
        assertEquals(7.00, valueOf(row("Customer tips", "799", "\$7.00")))
        assertEquals(
            1234.0,
            valueOf(
                row("Customer tips", "1,234"),
                nav = "nextSiblingMatchingRegex(^[\\d,]+\$)",
            ),
        )
    }

    @Test
    fun `the scan starts from the anchor's OWN position, not an equals-twin's`() {
        // #1029 E2: `UiNode.equals` compares this node's own fields and NOT its children, so two
        // wrapper Views differing only in what they contain are EQUAL. `sibling(offset)` resolves
        // its origin with `List.indexOf` — structural equality — and would start the scan from the
        // twin, one slot early; with a row-width cap that silently loses the value. The walk is by
        // referential identity for exactly this reason.
        val emptyTwin = UiNode(className = "Row")
        val anchorRow = UiNode(className = "Row", children = listOf(UiNode(text = "Customer tips")))
        val tree = UiNode(
            className = "Sheet",
            children = listOf(emptyTwin, anchorRow, UiNode(text = "799"), UiNode(text = "\$7.00")),
        ).restoreParents()

        assertEquals("the twins must actually be equal for this to test anything", emptyTwin, anchorRow)
        assertEquals(
            7.00,
            valueOf(
                tree,
                nav = cappedNav(2),
                find = """{ "all": [ { "hasClassName": "Row" }, { "hasChildren": true } ] }""",
            ),
        )
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
