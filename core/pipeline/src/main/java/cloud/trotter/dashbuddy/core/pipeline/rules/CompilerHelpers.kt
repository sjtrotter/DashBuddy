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
