package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Package-level compiler helpers shared across [PredicateCompiler] and
 * [ParseExpressionCompiler] (#239 extraction from `RuleCompiler`, pure move —
 * no behavior change).
 */

/** Read a named property off a [UiNode] — the `read:` vocabulary shared by
 *  several parse expressions ([ParseExpressionCompiler]). */
internal fun readProperty(node: UiNode, prop: String): String? = when (prop) {
    "text" -> node.text
    "allText" -> node.allText.joinToString("")
    "contentDescription" -> node.contentDescription
    "stateDescription" -> node.stateDescription
    "viewIdResourceName" -> node.viewIdResourceName
    "className" -> node.className
    else -> throw RuleCompileException("Unknown read property: '$prop'")
}

/**
 * How many FOLLOWING siblings `nextSiblingMatchingRegex(...)` will look at (#1029, bounded
 * ingestion) — the DEFAULT, and the hard ceiling on a rule-declared cap. A label→value pair on a
 * flat row is 1–3 siblings apart in every fielded render; 8 leaves headroom for an icon/divider or
 * two without letting a rule walk an arbitrarily wide container — the scan runs per frame on the
 * classification thread, and each step costs a (budgeted) regex match.
 *
 * A rule that knows its row is tighter says so: `nextSiblingMatchingRegex(<pattern>, <n>)` caps the
 * scan at `n`. That is a CORRECTNESS control, not just a bound — on the flattened 8.93.7 breakdown
 * `['Customer tips', '799', 'Peak pay', '$1.00']` (the tips value simply missing) an unbounded scan
 * walks past the row and returns Peak pay's `$1.00` AS the tip: fail-WRONG, and invisible to the
 * `sumApproxEquals` validator because `appPay` is null on that build. A cap of 2 stops at the row.
 */
internal const val MAX_SIBLING_SCAN = 8

/**
 * Splits a trailing integer cap off a `nextSiblingMatchingRegex` argument: `<pattern>, <n>`. A
 * pattern that merely CONTAINS a comma is unaffected — the anchor requires the argument to END in
 * `, <digits>`, and a shape pattern ends in its own anchor, never a digit.
 */
private val SIBLING_SCAN_CAP = Regex("^(.*),\\s*(\\d+)$")

/** Compile a navigation spec to a node closure — unknown verbs throw HERE,
 *  at rule-load time, never during a live match (#362). */
internal fun compileNavigation(navSpec: JsonElement): (UiNode) -> UiNode? {
    val nav = navSpec.jsonPrimitive.content
    return when {
        nav == "parent" -> { node -> node.parent }
        nav.startsWith("ancestor(") -> {
            val n = nav.removePrefix("ancestor(").removeSuffix(")").toIntOrNull() ?: 1
            ;{ node -> node.ancestor(n) }
        }
        nav.startsWith("sibling(") -> {
            val offset = nav.removePrefix("sibling(").removeSuffix(")").toIntOrNull() ?: 1
            ;{ node -> node.sibling(offset) }
        }
        // #1029 — the SHAPE-anchored sibling read: `nextSiblingMatchingRegex(<pattern>[, <n>])`.
        // `sibling(N)` addresses a slot by POSITION, which is only sound while the row's shape is
        // fixed; DoorDash 8.93.7 flattened the receipt breakdown into id-less siblings
        // ('Customer tips', '799', '$7.00') and `sibling(1)` off the label then read the stray
        // '799' — a type-code node that older builds render as a `pay_line_item_title` — through
        // `parseCurrency` as a fabricated $799.00 tip. This scans the FOLLOWING siblings in order
        // and returns the first whose own `text` FULL-matches the rule's pattern, so the rule
        // states the shape it expects ("a currency figure") instead of guessing an offset.
        //
        // The optional trailing integer caps the scan (default and ceiling [MAX_SIBLING_SCAN]) so
        // a rule can say how far its OWN row extends — see the constant for why that is a
        // correctness control, not merely a bound. A cap outside 1..MAX_SIBLING_SCAN is an
        // authoring error and fails the rule LOAD. Siblings are walked by REFERENTIAL identity
        // ([UiNode.followingSiblings]), not `sibling(offset)`'s structural `indexOf`: a flattened
        // row routinely holds twin blank wrappers, and a structural lookup would silently start
        // the scan from the wrong node. The pattern goes through [RegexSafety] here, at load time,
        // so an over-long/ReDoS-prone pattern is a loud [RuleCompileException] and never a
        // hot-path hang. Fail-null when nothing matches.
        nav.startsWith("nextSiblingMatchingRegex(") -> {
            val arg = nav.removePrefix("nextSiblingMatchingRegex(").removeSuffix(")")
            val capMatch = SIBLING_SCAN_CAP.matchEntire(arg)
            val pattern = capMatch?.groupValues?.get(1) ?: arg
            val cap = capMatch?.groupValues?.get(2)?.toIntOrNull() ?: MAX_SIBLING_SCAN
            if (cap < 1 || cap > MAX_SIBLING_SCAN) {
                throw RuleCompileException(
                    "nextSiblingMatchingRegex scan cap must be 1..$MAX_SIBLING_SCAN, got $cap",
                    isolable = true, // authoring typo — the rule isolates (#293 item 4)
                )
            }
            val regex = compileRegex(pattern)
            ;{ node ->
                node.followingSiblings().asSequence().take(cap)
                    .firstOrNull { s -> s.text?.let(regex::matches) == true }
            }
        }
        nav.startsWith("findChild(") -> {
            val idSuffix = nav.removePrefix("findChild(").removeSuffix(")")
            ;{ node -> node.findChildById(idSuffix) }
        }
        nav.startsWith("findDescendant(") -> {
            val idSuffix = nav.removePrefix("findDescendant(").removeSuffix(")")
            ;{ node -> node.findDescendantById(idSuffix) }
        }
        else -> throw RuleCompileException(
            "Unknown navigation: '$nav'",
            isolable = true, // authoring typo — the rule isolates (#293 item 4)
        )
    }
}

/** Resolve a JSON `literal:` value to its Kotlin scalar. */
internal fun resolveLiteral(json: JsonElement): Any? = when (json) {
    is kotlinx.serialization.json.JsonPrimitive -> when {
        json.booleanOrNull != null -> json.booleanOrNull
        json.intOrNull != null -> json.intOrNull
        json.longOrNull != null -> json.longOrNull
        json.isString -> json.content
        else -> json.content
    }
    else -> json.toString()
}

/**
 * Compile a rule-supplied regex through the [RegexSafety] guard (length cap
 * + ReDoS rejection, #418). [RuleCompiler.compileRegex] is the facade's own
 * one-line forward to [RegexSafety] (unchanged by this extraction); this
 * package-level twin is what [PredicateCompiler] and [ParseExpressionCompiler]
 * call directly so neither needs a dependency on the [RuleCompiler] facade —
 * both ultimately hit the SAME [RegexSafety] SSOT, never each other.
 */
internal fun compileRegex(pattern: String): BoundedRegex = RegexSafety.compileRegex(pattern)
