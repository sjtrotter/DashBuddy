package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compiles a parsed `rules.json` [JsonElement] tree into typed lambda rulesets.
 *
 * The compilation happens once at startup; the resulting lambdas are pure JVM closures
 * with no further JSON parsing on the hot path.
 *
 * Security caps enforced at compile time:
 * - [MAX_DEPTH] — maximum nesting depth of predicate objects (prevents stack overflows)
 * - [MAX_REGEX_LENGTH] — maximum characters in any regex pattern (mitigates ReDoS)
 *
 * This object knows about [Screen], [ClickInfo], and [NotificationInfo] by design —
 * the target name → domain type mapping is a fixed table; new subtypes require an app update.
 */
object RuleCompiler {

    const val MAX_DEPTH = 20
    const val MAX_REGEX_LENGTH = 200

    // ==========================================================================
    //  Screen rules
    // ==========================================================================

    fun compileScreenRules(array: JsonArray): List<CompiledScreenRule> =
        array.map { compileScreenRule(it.jsonObject) }

    private fun compileScreenRule(obj: JsonObject): CompiledScreenRule {
        val id = obj["id"]!!.jsonPrimitive.content
        val priority = obj["priority"]!!.jsonPrimitive.int
        val overrideable = obj["overrideable"]?.jsonPrimitive?.booleanOrNull ?: true

        val branches: List<CompiledBranch> = if ("branches" in obj) {
            obj["branches"]!!.jsonArray.map { compileBranch(it.jsonObject) }
        } else {
            listOf(compileBranch(obj))
        }

        return CompiledScreenRule(id, priority, overrideable, branches)
    }

    private fun compileBranch(obj: JsonObject): CompiledBranch {
        val targetName = obj["target"]!!.jsonPrimitive.content
        val target = try {
            Screen.valueOf(targetName)
        } catch (e: IllegalArgumentException) {
            throw RuleCompileException("Unknown Screen target: '$targetName'", e)
        }
        val guards = obj["guards"]?.jsonArray?.map { compileTreePred(it) } ?: emptyList()
        val condition = compileTreePred(obj["if"]!!)
        return CompiledBranch(target, guards, condition)
    }

    // ==========================================================================
    //  Click rules
    // ==========================================================================

    fun compileClickRules(array: JsonArray): List<CompiledClickRule> =
        array.map { compileClickRule(it.jsonObject) }

    private fun compileClickRule(obj: JsonObject): CompiledClickRule {
        val id = obj["id"]!!.jsonPrimitive.content
        val priority = obj["priority"]!!.jsonPrimitive.int
        val overrideable = obj["overrideable"]?.jsonPrimitive?.booleanOrNull ?: true
        val targetName = obj["target"]!!.jsonPrimitive.content
        val condition = compileNodePred(obj["if"]!!)

        val factory: (UiNode) -> ClickInfo = when (targetName) {
            "AcceptOffer" -> { _ -> ClickInfo.AcceptOffer }
            "DeclineOffer" -> { _ -> ClickInfo.DeclineOffer }
            "ArrivedAtStore" -> { _ -> ClickInfo.ArrivedAtStore }
            else -> throw RuleCompileException("Unknown click target: '$targetName'")
        }

        return CompiledClickRule(id, priority, overrideable, condition, factory)
    }

    // ==========================================================================
    //  Notification rules
    // ==========================================================================

    fun compileNotificationRules(array: JsonArray): List<CompiledNotificationRule> =
        array.map { compileNotificationRule(it.jsonObject) }

    private fun compileNotificationRule(obj: JsonObject): CompiledNotificationRule {
        val id = obj["id"]!!.jsonPrimitive.content
        val priority = obj["priority"]!!.jsonPrimitive.int
        val overrideable = obj["overrideable"]?.jsonPrimitive?.booleanOrNull ?: true
        val targetName = obj["target"]!!.jsonPrimitive.content
        val ifJson = obj["if"]
        val extract = obj["extract"] as? JsonObject

        val classify: (RawNotificationData) -> NotificationInfo? = when {
            targetName == "AdditionalTip" && extract != null ->
                compileAdditionalTipRule(ifJson!!, extract)

            targetName == "NewOrder" -> {
                val pred = ifJson?.let { compileNotifPred(it) }
                ;{ raw -> if (pred == null || pred(raw)) NotificationInfo.NewOrder else null }
            }

            targetName == "ScheduledDashExpired" -> {
                val pred = ifJson?.let { compileNotifPred(it) }
                ;{ raw -> if (pred == null || pred(raw)) NotificationInfo.ScheduledDashExpired else null }
            }

            else -> throw RuleCompileException("Unknown notification target: '$targetName'")
        }

        return CompiledNotificationRule(id, priority, overrideable, classify)
    }

    /**
     * AdditionalTip requires regex group extraction. The `if` block must use
     * `anyFieldMatchesRegex` so the same regex that matches can also extract groups.
     */
    private fun compileAdditionalTipRule(
        ifJson: JsonElement,
        @Suppress("UNUSED_PARAMETER") extract: JsonObject,
    ): (RawNotificationData) -> NotificationInfo? {
        val ifObj = ifJson.jsonObject
        val pattern = ifObj["anyFieldMatchesRegex"]?.jsonPrimitive?.content
            ?: throw RuleCompileException("AdditionalTip rule must use anyFieldMatchesRegex in 'if'")
        val regex = compileRegex(pattern)

        return { raw ->
            run {
                val m = regex.find(raw.toFullString()) ?: return@run null
                val amount = m.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return@run null
                val storeName = m.groupValues.getOrNull(2)?.trim() ?: return@run null
                val deliveredAt = m.groupValues.getOrNull(3)?.trim() ?: return@run null
                NotificationInfo.AdditionalTip(amount, storeName, deliveredAt)
            }
        }
    }

    // ==========================================================================
    //  Tree predicate compiler  (operates on full UiNode tree)
    // ==========================================================================

    /**
     * Compile a tree-level predicate from a [JsonElement].
     *
     * Tree predicates: `exists`, `notExists`, `allTextContains`, `allTextContainsAll`,
     * `allTextContainsAny`, `all`, `any`, `not`.
     *
     * NOTE: All when-branches that end with a lambda literal use a leading `;` to prevent
     * Kotlin from parsing the lambda as a trailing argument to the preceding expression.
     */
    fun compileTreePred(json: JsonElement, depth: Int = 0): (UiNode) -> Boolean {
        if (depth > MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=$MAX_DEPTH")

        val obj = json as? JsonObject
            ?: throw RuleCompileException("Tree predicate must be a JSON object, got: $json")
        val key = obj.keys.firstOrNull()
            ?: throw RuleCompileException("Empty tree predicate object")
        val value = obj[key]!!

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
                val text = (value as? JsonPrimitive)?.content?.lowercase()
                    ?: throw RuleCompileException("allTextContains requires a string value")
                ;{ tree -> tree.allText.joinToString(" | ").lowercase().contains(text) }
            }
            "allTextContainsAll" -> {
                val texts = (value as? JsonArray)
                    ?.map { (it as JsonPrimitive).content.lowercase() }
                    ?: throw RuleCompileException("allTextContainsAll requires an array")
                ;{ tree ->
                    val joined = tree.allText.joinToString(" | ").lowercase()
                    texts.all { joined.contains(it) }
                }
            }
            "allTextContainsAny" -> {
                val texts = (value as? JsonArray)
                    ?.map { (it as JsonPrimitive).content.lowercase() }
                    ?: throw RuleCompileException("allTextContainsAny requires an array")
                ;{ tree ->
                    val joined = tree.allText.joinToString(" | ").lowercase()
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
    //  Node predicate compiler  (operates on a single UiNode)
    // ==========================================================================

    /**
     * Compile a node-level predicate from a [JsonElement].
     *
     * Used inside `exists`/`notExists` (tree → node transition), and directly for click rules
     * (which operate on a single tapped node rather than a full tree).
     *
     * NOTE: All when-branches that end with a lambda literal use a leading `;` to prevent
     * Kotlin from parsing the lambda as a trailing argument to the preceding expression.
     */
    fun compileNodePred(json: JsonElement, depth: Int = 0): (UiNode) -> Boolean {
        if (depth > MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=$MAX_DEPTH")

        val obj = json as? JsonObject
            ?: throw RuleCompileException("Node predicate must be a JSON object, got: $json")
        val key = obj.keys.firstOrNull()
            ?: throw RuleCompileException("Empty node predicate object")
        val value = obj[key]!!

        return when (key) {
            // --- ID predicates ---
            "hasIdSuffix" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.viewIdResourceName?.endsWith(s, ignoreCase = true) == true }
            }
            "hasIdExact" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.viewIdResourceName == s }
            }
            "hasIdContaining" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.viewIdResourceName?.contains(s) == true }
            }
            "hasNoId" ->
                { node -> node.viewIdResourceName.isNullOrBlank() }

            // --- Text predicates ---
            "hasText" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.text?.equals(s, ignoreCase = true) == true }
            }
            "hasTextCaseSensitive" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.text == s }
            }
            "hasTextContaining" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.text?.contains(s, ignoreCase = true) == true }
            }
            "hasTextStartsWith" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.text?.startsWith(s, ignoreCase = true) == true }
            }
            "hasTextMatchesRegex" -> {
                val regex = compileRegex((value as JsonPrimitive).content)
                ;{ node -> node.text?.let { str -> regex.containsMatchIn(str) } == true }
            }

            // --- Content description predicates ---
            "hasDesc" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.contentDescription?.equals(s, ignoreCase = true) == true }
            }
            "hasDescContaining" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.contentDescription?.contains(s, ignoreCase = true) == true }
            }

            // --- State description predicates ---
            "hasStateDescription" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.stateDescription?.equals(s, ignoreCase = true) == true }
            }
            "hasStateDescriptionContaining" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.stateDescription?.contains(s, ignoreCase = true) == true }
            }

            // --- Class name predicates ---
            "hasClassName" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.className == s }
            }
            "hasClassNameEndsWith" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.className?.endsWith(s, ignoreCase = true) == true }
            }

            // --- Boolean flag predicates (value is ignored; presence of key is the signal) ---
            "isClickable" -> { node -> node.isClickable }
            "isEnabled" -> { node -> node.isEnabled }
            "isChecked" -> { node -> node.isChecked != 0 }
            "hasChildren" -> { node -> node.children.isNotEmpty() }
            "isLeaf" -> { node -> node.children.isEmpty() }

            // --- Logical combinators (node-level: children are also node predicates) ---
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

    /**
     * Compile a notification-level predicate from a [JsonElement].
     *
     * Predicates operate on [RawNotificationData] flat scalar fields.
     *
     * NOTE: All when-branches that end with a lambda literal use a leading `;` to prevent
     * Kotlin from parsing the lambda as a trailing argument to the preceding expression.
     */
    fun compileNotifPred(json: JsonElement, depth: Int = 0): (RawNotificationData) -> Boolean {
        if (depth > MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=$MAX_DEPTH")

        val obj = json as? JsonObject
            ?: throw RuleCompileException("Notification predicate must be a JSON object")
        val key = obj.keys.firstOrNull()
            ?: throw RuleCompileException("Empty notification predicate object")
        val value = obj[key]!!

        return when (key) {
            "titleEquals" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.title?.equals(s, ignoreCase = true) == true }
            }
            "titleContains" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.title?.contains(s, ignoreCase = true) == true }
            }
            "titleMatchesRegex" -> {
                val regex = compileRegex((value as JsonPrimitive).content)
                ;{ raw -> raw.title?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "textEquals" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.text?.equals(s, ignoreCase = true) == true }
            }
            "textContains" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.text?.contains(s, ignoreCase = true) == true }
            }
            "textMatchesRegex" -> {
                val regex = compileRegex((value as JsonPrimitive).content)
                ;{ raw -> raw.text?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "bigTextContains" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.bigText?.contains(s, ignoreCase = true) == true }
            }
            "bigTextMatchesRegex" -> {
                val regex = compileRegex((value as JsonPrimitive).content)
                ;{ raw -> raw.bigText?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "tickerTextContains" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.tickerText?.contains(s, ignoreCase = true) == true }
            }
            "tickerTextMatchesRegex" -> {
                val regex = compileRegex((value as JsonPrimitive).content)
                ;{ raw -> raw.tickerText?.let { str -> regex.containsMatchIn(str) } == true }
            }
            "isClearable" ->
                { raw -> raw.isClearable }
            "anyFieldContains" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.toFullString().contains(s, ignoreCase = true) }
            }
            "anyFieldContainsAll" -> {
                val texts = (value as? JsonArray)
                    ?.map { (it as JsonPrimitive).content }
                    ?: throw RuleCompileException("anyFieldContainsAll requires an array")
                ;{ raw ->
                    val full = raw.toFullString()
                    texts.all { full.contains(it, ignoreCase = true) }
                }
            }
            "anyFieldContainsAny" -> {
                val texts = (value as? JsonArray)
                    ?.map { (it as JsonPrimitive).content }
                    ?: throw RuleCompileException("anyFieldContainsAny requires an array")
                ;{ raw ->
                    val full = raw.toFullString()
                    texts.any { full.contains(it, ignoreCase = true) }
                }
            }
            "anyFieldMatchesRegex" -> {
                val regex = compileRegex((value as JsonPrimitive).content)
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

    // ==========================================================================
    //  Regex helper
    // ==========================================================================

    private fun compileRegex(pattern: String): Regex {
        if (pattern.length > MAX_REGEX_LENGTH)
            throw RuleCompileException(
                "Regex pattern length ${pattern.length} exceeds MAX_REGEX_LENGTH=$MAX_REGEX_LENGTH"
            )
        return try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (e: Exception) {
            throw RuleCompileException("Invalid regex pattern: '$pattern'", e)
        }
    }
}
