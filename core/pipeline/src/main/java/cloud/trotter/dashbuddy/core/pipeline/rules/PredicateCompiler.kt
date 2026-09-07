package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Compiles the tree/node/notification predicate vocabularies (#239 extraction from
 * `RuleCompiler`, pure move — no behavior change). [RuleCompiler] keeps the public
 * `compileTreePred`/`compileNodePred`/`compileNotifPred` entry points as thin
 * delegates so every external call site (tests, [Ruleset], [ParseExpressionCompiler])
 * is unaffected.
 */
internal object PredicateCompiler {

    // ==========================================================================
    //  Tree predicate compiler (operates on full UiNode tree)
    // ==========================================================================

    fun compileTreePred(json: JsonElement, depth: Int = 0): (UiNode) -> Boolean {
        if (depth > RuleCompiler.MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=${RuleCompiler.MAX_DEPTH}")

        val obj = json as? JsonObject
            ?: throw RuleCompileException("Tree predicate must be a JSON object, got: $json")
        val key = obj.keys.firstOrNull()
            ?: throw RuleCompileException("Empty tree predicate object")
        requireSinglePredicateKey(obj, "Tree predicate")
        val value = obj.getValue(key)

        return when (key) {
            "exists" -> {
                val nodePred = compileNodePred(value, depth + 1)
                ;{ tree -> tree.findNode(nodePred) != null }
            }
            "notExists" -> {
                val nodePred = compileNodePred(value, depth + 1)
                ;{ tree -> tree.findNode(nodePred) == null }
            }
            "allTextContains" -> {
                val text = (value as? JsonPrimitive)?.content?.lowercase(Locale.ROOT)
                    ?: throw RuleCompileException("allTextContains requires a string value")
                ;{ tree -> tree.allTextLowerJoined.contains(text) }
            }
            "allTextContainsAll" -> {
                val texts = (value as? JsonArray)
                    ?.map { primOf(it, key).content.lowercase(Locale.ROOT) }
                    ?: throw RuleCompileException("allTextContainsAll requires an array")
                ;{ tree ->
                    val joined = tree.allTextLowerJoined
                    texts.all { joined.contains(it) }
                }
            }
            "allTextContainsAny" -> {
                val texts = (value as? JsonArray)
                    ?.map { primOf(it, key).content.lowercase(Locale.ROOT) }
                    ?: throw RuleCompileException("allTextContainsAny requires an array")
                ;{ tree ->
                    val joined = tree.allTextLowerJoined
                    texts.any { joined.contains(it) }
                }
            }
            "all" -> {
                val preds = (value as? JsonArray)
                    ?.map { compileTreePred(it, depth + 1) }
                    ?: throw RuleCompileException("all requires an array")
                ;{ tree -> preds.all { it(tree) } }
            }
            "any" -> {
                val preds = (value as? JsonArray)
                    ?.map { compileTreePred(it, depth + 1) }
                    ?: throw RuleCompileException("any requires an array")
                ;{ tree -> preds.any { it(tree) } }
            }
            "not" -> {
                val pred = compileTreePred(value, depth + 1)
                ;{ tree -> !pred(tree) }
            }
            else -> throw RuleCompileException("Unknown tree predicate key: '$key'")
        }
    }

    // ==========================================================================
    //  Node predicate compiler (operates on a single UiNode)
    // ==========================================================================

    fun compileNodePred(json: JsonElement, depth: Int = 0): (UiNode) -> Boolean {
        if (depth > RuleCompiler.MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=${RuleCompiler.MAX_DEPTH}")

        val obj = json as? JsonObject
            ?: throw RuleCompileException("Node predicate must be a JSON object, got: $json")
        val key = obj.keys.firstOrNull()
            ?: throw RuleCompileException("Empty node predicate object")
        requireSinglePredicateKey(obj, "Node predicate")
        val value = obj.getValue(key)

        return when (key) {
            "hasIdSuffix" -> {
                val s = primOf(value, key).content
                ;{ node -> node.viewIdResourceName?.endsWith(s, ignoreCase = true) == true }
            }
            "hasIdExact" -> {
                val s = primOf(value, key).content
                ;{ node -> node.viewIdResourceName == s }
            }
            "hasIdContaining" -> {
                val s = primOf(value, key).content
                ;{ node -> node.viewIdResourceName?.contains(s) == true }
            }
            "hasNoId" -> {
                val want = boolFlag(value, key)
                ;{ node -> node.viewIdResourceName.isNullOrBlank() == want }
            }

            "hasText" -> {
                val s = primOf(value, key).content
                ;{ node -> node.text?.equals(s, ignoreCase = true) == true }
            }
            "hasTextCaseSensitive" -> {
                val s = primOf(value, key).content
                ;{ node -> node.text == s }
            }
            "hasTextContaining" -> {
                val s = primOf(value, key).content
                ;{ node -> node.text?.contains(s, ignoreCase = true) == true }
            }
            "hasTextStartsWith" -> {
                val s = primOf(value, key).content
                ;{ node -> node.text?.startsWith(s, ignoreCase = true) == true }
            }
            "hasTextMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ node -> node.text?.let { str -> regex.containsMatchIn(str) } == true }
            }

            // Matches a node whose IMMEDIATELY PRECEDING sibling's text equals
            // the value, case-insensitive (#860). The label→value predicate: a
            // Compose/RN address block renders `<label TextView><value TextView>`
            // pairs with NO viewId on either node, so a value carrying free-form
            // PII (an apartment-complex "Building Name") is unreachable by every
            // other predicate — its own text has no stable shape, and its id is
            // absent. Anchoring on the LABEL (app chrome, stable vocabulary) is
            // the only tight way to name it; the label string stays rule DATA, so
            // this predicate is platform-agnostic (principle 8).
            "hasPrecedingSiblingText" -> {
                val s = primOf(value, key).content
                ;{ node -> precedingSibling(node)?.text?.equals(s, ignoreCase = true) == true }
            }

            // The mirror of [hasPrecedingSiblingText] for the block whose PII value
            // comes FIRST (#886). An id-less address block renders
            // `<line-1 TextView><line-2 TextView>` with no label at all: line 1 is a
            // street line OR — when the destination is a business/venue — the venue
            // NAME, free-form proper-noun text with no id, no digits and no stable
            // shape, and no preceding sibling to anchor on (it is child 0). Its
            // FOLLOWING sibling is the city/ST/ZIP line, which DOES have a stable
            // shape — so the anchor is a regex on the next sibling's text. Text
            // shape is rule DATA (no platform literal, principle 8); the pattern
            // runs through the same [compileRegex] length/parse seam as
            // `hasTextMatchesRegex`, onto the same linear-time engine (#1053). Redaction evaluates every entry against the
            // ORIGINAL tree (the mask is applied to a copy), so a sibling that is
            // itself masked by another entry still reads raw here.
            "hasFollowingSiblingTextMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ node -> followingSibling(node)?.text?.let { str -> regex.containsMatchIn(str) } == true }
            }

            // Matches when *any* text node anywhere in this subtree (including
            // the node itself and any descendant's text or contentDescription)
            // equals the value, case-insensitive. Necessary for buttons whose
            // text lives in a child TextView (e.g. the DoorDash confirm-decline
            // Button has no text on the outer node; the text is in a nested
            // textView_prism_button_title child).
            "hasAnyText" -> {
                val s = primOf(value, key).content
                ;{ node -> node.allText.any { it.equals(s, ignoreCase = true) } }
            }

            "hasDesc" -> {
                val s = primOf(value, key).content
                ;{ node -> node.contentDescription?.equals(s, ignoreCase = true) == true }
            }
            "hasDescContaining" -> {
                val s = primOf(value, key).content
                ;{ node -> node.contentDescription?.contains(s, ignoreCase = true) == true }
            }

            "hasStateDescription" -> {
                val s = primOf(value, key).content
                ;{ node -> node.stateDescription?.equals(s, ignoreCase = true) == true }
            }
            "hasStateDescriptionContaining" -> {
                val s = primOf(value, key).content
                ;{ node -> node.stateDescription?.contains(s, ignoreCase = true) == true }
            }

            "hasClassName" -> {
                val s = primOf(value, key).content
                ;{ node -> node.className == s }
            }
            "hasClassNameEndsWith" -> {
                val s = primOf(value, key).content
                ;{ node -> node.className?.endsWith(s, ignoreCase = true) == true }
            }

            "isClickable" -> { val want = boolFlag(value, key); { node -> node.isClickable == want } }
            "isEnabled" -> { val want = boolFlag(value, key); { node -> node.isEnabled == want } }
            "isChecked" -> { val want = boolFlag(value, key); { node -> (node.isChecked != 0) == want } }
            "hasChildren" -> { val want = boolFlag(value, key); { node -> node.children.isNotEmpty() == want } }
            "isLeaf" -> { val want = boolFlag(value, key); { node -> node.children.isEmpty() == want } }

            "all" -> {
                val preds = (value as? JsonArray)
                    ?.map { compileNodePred(it, depth + 1) }
                    ?: throw RuleCompileException("all requires an array")
                ;{ node -> preds.all { it(node) } }
            }
            "any" -> {
                val preds = (value as? JsonArray)
                    ?.map { compileNodePred(it, depth + 1) }
                    ?: throw RuleCompileException("any requires an array")
                ;{ node -> preds.any { it(node) } }
            }
            "not" -> {
                val pred = compileNodePred(value, depth + 1)
                ;{ node -> !pred(node) }
            }

            else -> throw RuleCompileException("Unknown node predicate key: '$key'")
        }
    }

    // ==========================================================================
    //  Notification predicate compiler
    // ==========================================================================

    fun compileNotifPred(json: JsonElement, depth: Int = 0): (RawNotificationData) -> Boolean {
        if (depth > RuleCompiler.MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=${RuleCompiler.MAX_DEPTH}")

        val obj = json as? JsonObject
            ?: throw RuleCompileException("Notification predicate must be a JSON object")
        val key = obj.keys.firstOrNull()
            ?: throw RuleCompileException("Empty notification predicate object")
        requireSinglePredicateKey(obj, "Notification predicate")
        val value = obj.getValue(key)

        return when (key) {
            "titleEquals" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.title?.equals(s, ignoreCase = true) == true }
            }
            "titleContains" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.title?.contains(s, ignoreCase = true) == true }
            }
            "titleMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ raw -> raw.title?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "textEquals" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.text?.equals(s, ignoreCase = true) == true }
            }
            "textContains" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.text?.contains(s, ignoreCase = true) == true }
            }
            "textMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ raw -> raw.text?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "bigTextContains" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.bigText?.contains(s, ignoreCase = true) == true }
            }
            "bigTextMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ raw -> raw.bigText?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "tickerTextContains" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.tickerText?.contains(s, ignoreCase = true) == true }
            }
            "tickerTextMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ raw -> raw.tickerText?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "isClearable" -> {
                val want = boolFlag(value, key)
                ;{ raw -> raw.isClearable == want }
            }
            "isOngoing" -> {
                val want = boolFlag(value, key)
                ;{ raw -> raw.isOngoing == want }
            }
            "channelIdEquals" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.channelId == s }
            }
            "channelIdContains" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.channelId?.contains(s, ignoreCase = true) == true }
            }
            "categoryEquals" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.category == s }
            }
            "hasAction" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.actionLabels.any { it.contains(s, ignoreCase = true) } }
            }
            "anyFieldContains" -> {
                val s = primOf(value, key).content
                ;{ raw -> raw.toFullString().contains(s, ignoreCase = true) }
            }
            "anyFieldContainsAll" -> {
                val texts = (value as? JsonArray)
                    ?.map { primOf(it, key).content }
                    ?: throw RuleCompileException("anyFieldContainsAll requires an array")
                ;{ raw ->
                    val full = raw.toFullString()
                    texts.all { full.contains(it, ignoreCase = true) }
                }
            }
            "anyFieldContainsAny" -> {
                val texts = (value as? JsonArray)
                    ?.map { primOf(it, key).content }
                    ?: throw RuleCompileException("anyFieldContainsAny requires an array")
                ;{ raw ->
                    val full = raw.toFullString()
                    texts.any { full.contains(it, ignoreCase = true) }
                }
            }
            "anyFieldMatchesRegex" -> {
                val regex = compileRegex(primOf(value, key).content)
                ;{ raw -> regex.containsMatchIn(raw.toFullString()) }
            }
            "all" -> {
                val preds = (value as? JsonArray)
                    ?.map { compileNotifPred(it, depth + 1) }
                    ?: throw RuleCompileException("all requires an array")
                ;{ raw -> preds.all { it(raw) } }
            }
            "any" -> {
                val preds = (value as? JsonArray)
                    ?.map { compileNotifPred(it, depth + 1) }
                    ?: throw RuleCompileException("any requires an array")
                ;{ raw -> preds.any { it(raw) } }
            }
            "not" -> {
                val pred = compileNotifPred(value, depth + 1)
                ;{ raw -> !pred(raw) }
            }
            else -> throw RuleCompileException("Unknown notification predicate key: '$key'")
        }
    }

    /**
     * The sibling immediately BEFORE [node], resolved by REFERENTIAL identity
     * (#860). Deliberately does NOT reuse `UiNode.sibling(-1)`: that helper
     * resolves the node's index with `List.indexOf`, i.e. structural equality —
     * so a structurally identical earlier sibling would silently resolve the
     * WRONG index and the predicate would fail to match, UNDER-masking a PII
     * node. A privacy control must not depend on structural uniqueness.
     *
     * Since #1029 the identity walk itself has ONE owner, [UiNode.precedingSibling] /
     * [UiNode.followingSiblings], shared with the `nextSiblingMatchingRegex` navigate — the two
     * had drifted into private copies of the same three lines. Returns null when the tree has no
     * parent back-references wired (`restoreParents`, always called by `AccessibilityNodeMapper`
     * and `UiNodeDto.toDomain`) or the node is first — fail-closed for recognition, and for
     * redaction a null simply means "no match here", with the `CustomerTextMarkers` backstop
     * still downstream.
     */
    private fun precedingSibling(node: UiNode): UiNode? = node.precedingSibling()

    /**
     * The sibling immediately AFTER [node] (#886) — [precedingSibling]'s mirror,
     * through the same [UiNode.followingSiblings] identity walk and for the same
     * reason: a structural-equality lookup would resolve a duplicated sibling to
     * the wrong index and UNDER-mask a PII node. Returns null with no parent
     * back-references or when the node is last.
     */
    private fun followingSibling(node: UiNode): UiNode? = node.followingSiblings().firstOrNull()

    // -- #293 robustness helpers, predicate-scoped ------------------------------

    /**
     * The scalar of a single-key predicate, or a typed [RuleCompileException]
     * (#293 item 3). Replaces the bare `value as JsonPrimitive` casts scattered
     * through the predicate compilers so a mistyped rule (a nested object where a
     * string is expected) fails rule LOAD with a clear message instead of throwing
     * a raw [ClassCastException] out of the compiler.
     */
    private fun primOf(value: JsonElement, key: String): JsonPrimitive =
        value as? JsonPrimitive
            ?: throw RuleCompileException(
                "Predicate '$key' requires a scalar value, got: $value",
                isolable = true,
            )

    /**
     * A boolean-flag predicate's declared value (#293 item 2), typed (#293 item 3).
     * `{"isClickable": false}` now matches NON-clickable nodes instead of silently
     * ignoring the value. A non-boolean value is a typed compile error, not a
     * silent default.
     */
    private fun boolFlag(value: JsonElement, key: String): Boolean =
        primOf(value, key).booleanOrNull
            ?: throw RuleCompileException(
                "Predicate '$key' requires a boolean value (true/false), got: $value",
                isolable = true,
            )

    /**
     * Enforce that a predicate object carries EXACTLY ONE key (#293 item 1). The
     * predicate vocabularies (tree/node/notification) are single-key by design
     * (see docs/rules.schema.json: "Object with exactly one key"); a second key
     * — `{"hasIdSuffix":"x","hasText":"y"}` — used to compile with only the first
     * key firing and the rest silently dropped. There are no auxiliary keys on
     * these predicates (case-insensitivity is intrinsic, not a `ignoreCase` flag),
     * so exactly-one is the correct contract. [what] names the vocabulary for the
     * error; the empty-object case keeps its own message.
     */
    private fun requireSinglePredicateKey(obj: JsonObject, what: String) {
        if (obj.size > 1) {
            throw RuleCompileException(
                "$what must have exactly one key, got ${obj.keys.toList()}",
                isolable = true,
            )
        }
    }
}
