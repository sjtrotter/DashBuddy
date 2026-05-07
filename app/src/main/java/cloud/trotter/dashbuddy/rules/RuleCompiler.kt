package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Compiles a parsed `rules.json` [JsonElement] tree into typed lambda rulesets.
 *
 * Supports both v1 (`if:`/`guards:`) and v2 (`bind:`/`reject:`/`require:`/`parse:`/`validate:`)
 * rule formats. v1 rules compile with no parse phase (produce empty fields).
 *
 * The compilation happens once at startup; the resulting lambdas are pure JVM closures
 * with no further JSON parsing on the hot path.
 *
 * Security caps enforced at compile time:
 * - [MAX_DEPTH] — maximum nesting depth of predicate objects
 * - [MAX_REGEX_LENGTH] — maximum characters in any regex pattern
 * - [MAX_EACH_SIZE] — maximum elements from an `each` expression
 */
object RuleCompiler {

    const val MAX_DEPTH = 20
    const val MAX_REGEX_LENGTH = 200
    private const val MAX_EACH_SIZE = 50

    // ==========================================================================
    //  Screen rules
    // ==========================================================================

    fun compileScreenRules(array: JsonArray): List<CompiledScreenRule> =
        array.map { compileScreenRule(it.jsonObject) }

    private fun compileScreenRule(obj: JsonObject): CompiledScreenRule {
        val id = obj["id"]!!.jsonPrimitive.content
        val priority = obj["priority"]!!.jsonPrimitive.int
        val overrideable = obj["overrideable"]?.jsonPrimitive?.booleanOrNull ?: true

        // Rule-level bindings (shared across branches)
        val ruleBindings = obj["bind"]?.jsonObject?.let { compileBindBlock(it) } ?: emptyList()

        val (ruleFlow, ruleModeHint) = parseStateBlock(obj["state"] as? JsonObject, id)

        val branches: List<CompiledBranch> = if ("branches" in obj) {
            obj["branches"]!!.jsonArray.map { compileBranch(it.jsonObject, ruleFlow, ruleModeHint) }
        } else {
            listOf(compileBranch(obj, ruleFlow, ruleModeHint, ruleId = id))
        }

        return CompiledScreenRule(id, priority, overrideable, ruleBindings, branches)
    }

    private fun compileBranch(
        obj: JsonObject,
        ruleFlow: Flow? = null,
        ruleModeHint: Mode? = null,
        ruleId: String? = null,
    ): CompiledBranch {
        val targetName = obj["target"]?.jsonPrimitive?.content
            ?: ruleId?.let { deriveTargetFromId(it) }
            ?: throw RuleCompileException("Branch has no 'target' field and no rule id to derive from")

        // Branch-level state overrides rule-level defaults
        val (branchFlow, branchModeHint) = parseStateBlock(obj["state"] as? JsonObject, "$targetName-branch")

        // Branch-level bindings
        val bindings = obj["bind"]?.jsonObject?.let { compileBindBlock(it) } ?: emptyList()

        // --- Phase 2: Reject ---
        // v2: "reject:" array of tree preds
        // v1 compat: "guards:" array of tree preds
        val rejectJson = obj["reject"] ?: obj["guards"]
        val rejectChecks = rejectJson?.jsonArray?.map { rejectEntry ->
            val treePred = compileTreePred(rejectEntry)
            val fn: (UiNode, Bindings) -> Boolean = { tree, _ -> treePred(tree) }
            fn
        } ?: emptyList()

        // --- Phase 3: Require ---
        // v2: "require:" tree pred (with optional $bound references)
        // v1 compat: "if:" tree pred
        val requireJson = obj["require"] ?: obj["if"]
            ?: throw RuleCompileException("Branch for '$targetName' has no 'require' or 'if' block")
        val requirePred = compileTreePred(requireJson)
        val requireCheck: (UiNode, Bindings) -> Boolean = { tree, _ -> requirePred(tree) }

        // --- Phase 4: Parse ---
        // Shape is declared at branch level (preferred) or inside parse block (legacy)
        val parseBlock = obj["parse"]?.jsonObject
        val parseShape = obj["shape"]?.jsonPrimitive?.content
            ?: parseBlock?.get("shape")?.jsonPrimitive?.content
        val parser: (UiNode, Bindings) -> Map<String, Any?> = if (parseBlock != null) {
            compileParseBlock(parseBlock)
        } else {
            { _, _ -> emptyMap() }
        }

        // --- Phase 5: Validate ---
        val validators = obj["validate"]?.jsonArray?.map { entry ->
            compileValidateEntry(entry.jsonObject)
        } ?: emptyList()

        // --- Actions (Phase 3 of the plan — stub for now) ---
        val actions = obj["actions"]?.jsonArray?.map { compileActionEntry(it.jsonObject) } ?: emptyList()

        return CompiledBranch(
            target = targetName,
            bindings = bindings,
            rejectChecks = rejectChecks,
            requireCheck = requireCheck,
            parser = parser,
            parseShape = parseShape,
            validators = validators,
            actions = actions,
            flow = branchFlow ?: ruleFlow,
            modeHint = branchModeHint ?: ruleModeHint,
        )
    }

    // ==========================================================================
    //  Bind block compiler
    // ==========================================================================

    private fun compileBindBlock(obj: JsonObject): List<Binding> = obj.entries.map { (name, spec) ->
        val specObj = spec.jsonObject
        val optional = specObj["optional"]?.jsonPrimitive?.booleanOrNull ?: false

        // The find specification uses node predicates
        val findSpec = specObj["find"] ?: throw RuleCompileException("Binding '$name' has no 'find'")
        val nodePred = compileNodePred(findSpec)
        val find: (UiNode) -> UiNode? = { tree -> tree.findNode(nodePred) }

        Binding(name, find, optional)
    }

    // ==========================================================================
    //  Parse block compiler
    // ==========================================================================

    /**
     * Compile a `parse:` block into a function that extracts field values from the tree.
     *
     * ```json
     * { "shape": "task", "fields": { "storeName": { ... }, "deadline": { ... } } }
     * ```
     */
    private fun compileParseBlock(parseObj: JsonObject): (UiNode, Bindings) -> Map<String, Any?> {
        val fieldsObj = parseObj["fields"]?.jsonObject ?: return { _, _ -> emptyMap() }

        val compiledFields: List<Pair<String, (UiNode, Bindings) -> Any?>> =
            fieldsObj.entries.map { (fieldName, fieldSpec) ->
                fieldName to compileParseExpression(fieldSpec)
            }

        return { tree, bindings ->
            val result = mutableMapOf<String, Any?>()
            for ((name, expr) in compiledFields) {
                result[name] = expr(tree, bindings)
            }
            result
        }
    }

    /**
     * Compile a single parse expression into a function.
     *
     * Parse expressions form a mini-language:
     * - `find:` / `findAll:` — locate node(s) by predicate
     * - `read:` — read a property from the found node
     * - `transform:` — apply transforms from TransformRegistry
     * - `textAfterLabel:` — UiNode.textAfterLabel helper
     * - `navigate:` — parent/ancestor/sibling traversal
     * - `each:` — iterate over findAll results
     * - `join:` — concatenate multiple expressions
     * - `coalesce:` — first non-null expression
     * - `presence:` — boolean: does predicate match?
     * - `conditionalEnum:` — if/then/else chain
     * - `fallback:` — expression with default value
     * - `rejectIf:` — null out if predicate matches
     * - `from:` — start from a bound node
     * - `literal:` — constant value
     */
    private fun compileParseExpression(spec: JsonElement): (UiNode, Bindings) -> Any? {
        if (spec is JsonPrimitive) {
            // Literal string value
            val value = spec.content
            return { _, _ -> value }
        }

        val obj = spec as? JsonObject
            ?: throw RuleCompileException("Parse expression must be an object or string literal")

        // Determine the starting node: from a binding or from the tree root
        val fromName = obj["from"]?.jsonPrimitive?.content?.removePrefix("$")

        // Build the core expression
        return when {
            "literal" in obj -> compileLiteral(obj)
            "find" in obj -> compileFind(obj, fromName)
            "findAll" in obj -> compileFindAll(obj, fromName)
            "textAfterLabel" in obj -> compileTextAfterLabel(obj, fromName)
            "presence" in obj -> compilePresence(obj, fromName)
            "conditionalEnum" in obj -> compileConditionalEnum(obj, fromName)
            "each" in obj -> compileEach(obj, fromName)
            "join" in obj -> compileJoin(obj)
            "coalesce" in obj -> compileCoalesce(obj)
            "read" in obj && fromName != null -> compileReadFromBinding(obj, fromName)
            else -> throw RuleCompileException("Unknown parse expression keys: ${obj.keys}")
        }
    }

    private fun compileLiteral(obj: JsonObject): (UiNode, Bindings) -> Any? {
        val value = obj["literal"]!!
        return when (value) {
            is JsonPrimitive -> {
                val v: Any? = when {
                    value.booleanOrNull != null -> value.booleanOrNull
                    value.intOrNull != null -> value.intOrNull
                    value.longOrNull != null -> value.longOrNull
                    else -> value.content
                }
                ;{ _, _ -> v }
            }
            else -> { _, _ -> value.toString() }
        }
    }

    /**
     * `find:` — locate a node, optionally read a property, optionally transform.
     */
    private fun compileFind(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["find"]!!)
        val readProp = obj["read"]?.jsonPrimitive?.content
        val transformSpec = obj["transform"]
        val fallbackVal = obj["fallback"]
        val rejectIfSpec = obj["rejectIf"]?.let { compileNodePred(it) }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            var foundNode = startNode.findNode(findPred)

            // Navigate from found node if specified
            val navSpec = obj["navigate"]
            if (foundNode != null && navSpec != null) {
                foundNode = applyNavigation(foundNode, navSpec)
            }

            // RejectIf: null out the found node if it matches
            if (foundNode != null && rejectIfSpec != null && rejectIfSpec(foundNode)) {
                foundNode = null
            }

            // Read property from the node
            val rawValue = if (foundNode != null && readProp != null) {
                readProperty(foundNode, readProp)
            } else if (foundNode != null && readProp == null) {
                // No read specified — return the node's text by default
                foundNode.text
            } else null

            // Apply transform
            val transformed = if (rawValue != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, rawValue)
            } else rawValue

            // Fallback
            transformed ?: fallbackVal?.let { resolveLiteral(it) }
        }
    }

    /**
     * `findAll:` — locate all matching nodes.
     */
    private fun compileFindAll(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["findAll"]!!)
        val readProp = obj["read"]?.jsonPrimitive?.content ?: "text"

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            val nodes = startNode.findNodes(findPred).take(MAX_EACH_SIZE)
            nodes.mapNotNull { readProperty(it, readProp) }
        }
    }

    /**
     * `textAfterLabel:` — use UiNode.textAfterLabel helper.
     */
    private fun compileTextAfterLabel(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val label = obj["textAfterLabel"]!!.jsonPrimitive.content
        val offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: 1
        val transformSpec = obj["transform"]

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            val rawValue = startNode.textAfterLabel(label, offset)
            if (rawValue != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, rawValue)
            } else rawValue
        }
    }

    /**
     * `presence:` — boolean: does a tree/node predicate match?
     */
    private fun compilePresence(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val presenceSpec = obj["presence"]!!.jsonObject
        val check = when {
            "exists" in presenceSpec -> {
                val nodePred = compileNodePred(presenceSpec["exists"]!!)
                val fn: (UiNode) -> Boolean = { tree -> tree.findNode(nodePred) != null }
                fn
            }
            "allTextContains" in presenceSpec -> {
                val text = presenceSpec["allTextContains"]!!.jsonPrimitive.content.lowercase()
                val fn: (UiNode) -> Boolean = { tree ->
                    tree.allText.joinToString(" | ").lowercase().contains(text)
                }
                fn
            }
            else -> {
                // Generic: compile as tree pred
                val pred = compileTreePred(JsonObject(presenceSpec))
                pred
            }
        }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            check(startNode)
        }
    }

    /**
     * `conditionalEnum:` — if/then/else chain returning a string.
     */
    private fun compileConditionalEnum(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val cases = obj["conditionalEnum"]!!.jsonArray
        data class Case(val check: (UiNode) -> Boolean, val value: String)

        val compiledCases = mutableListOf<Case>()
        var elseValue: String? = null

        for (entry in cases) {
            val entryObj = entry.jsonObject
            if ("else" in entryObj) {
                elseValue = entryObj["else"]!!.jsonPrimitive.content
            } else {
                val pred = compileTreePred(entryObj["if"]!!)
                val value = entryObj["then"]!!.jsonPrimitive.content
                compiledCases.add(Case(pred, value))
            }
        }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            compiledCases.firstOrNull { it.check(startNode) }?.value ?: elseValue
        }
    }

    /**
     * `each:` — iterate over findAll results, extract per-item fields.
     */
    private fun compileEach(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["each"]!!)
        val scopeSpec = obj["scope"]
        val excludeSpec = obj["exclude"]?.let { compileNodePred(it) }
        val extractObj = obj["extract"]?.jsonObject
            ?: throw RuleCompileException("'each' requires 'extract' block")

        val compiledExtract: List<Pair<String, (UiNode, Bindings) -> Any?>> =
            extractObj.entries.map { (name, spec) ->
                name to compileParseExpression(spec)
            }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            val rawNodes = startNode.findNodes(findPred).take(MAX_EACH_SIZE)

            // Optional scope: walk up to ancestor
            val scopedNodes = if (scopeSpec != null) {
                val ancestorN = scopeSpec.jsonPrimitive.content
                    .removePrefix("ancestor(").removeSuffix(")").toIntOrNull() ?: 1
                rawNodes.mapNotNull { it.ancestor(ancestorN) }.distinct()
            } else rawNodes

            // Optional exclude
            val filtered = if (excludeSpec != null) {
                scopedNodes.filter { !excludeSpec(it) }
            } else scopedNodes

            // Extract fields per item
            filtered.map { itemNode ->
                val itemFields = mutableMapOf<String, Any?>()
                for ((name, expr) in compiledExtract) {
                    // For each expressions, the itemNode becomes the "tree" for nested expressions
                    itemFields[name] = expr(itemNode, bindings)
                }
                itemFields
            }
        }
    }

    /**
     * `join:` — concatenate parts with a separator.
     */
    private fun compileJoin(obj: JsonObject): (UiNode, Bindings) -> Any? {
        val partsArray = obj["join"]!!.jsonArray
        val separator = obj["separator"]?.jsonPrimitive?.content ?: ""
        val skipNulls = obj["skipNulls"]?.jsonPrimitive?.booleanOrNull ?: true
        val transformSpec = obj["transform"]

        val compiledParts = partsArray.map { compileParseExpression(it) }

        return { tree, bindings ->
            val values = compiledParts.map { it(tree, bindings)?.toString() }
            val joined = if (skipNulls) {
                values.filterNotNull().joinToString(separator)
            } else {
                values.joinToString(separator) { it ?: "" }
            }
            val result: Any? = joined.ifBlank { null }
            if (result != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, result.toString())
            } else result
        }
    }

    /**
     * `coalesce:` — first non-null expression.
     */
    private fun compileCoalesce(obj: JsonObject): (UiNode, Bindings) -> Any? {
        val exprs = obj["coalesce"]!!.jsonArray.map { compileParseExpression(it) }

        return { tree, bindings ->
            exprs.firstNotNullOfOrNull { it(tree, bindings) }
        }
    }

    /**
     * Read a property from a bound node with optional transform.
     */
    private fun compileReadFromBinding(obj: JsonObject, fromName: String): (UiNode, Bindings) -> Any? {
        val readProp = obj["read"]!!.jsonPrimitive.content
        val transformSpec = obj["transform"]

        return { _, bindings ->
            val node = bindings[fromName]
            val rawValue = if (node != null) readProperty(node, readProp) else null
            if (rawValue != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, rawValue)
            } else rawValue
        }
    }

    // ==========================================================================
    //  Validate entry compiler
    // ==========================================================================

    private fun compileValidateEntry(obj: JsonObject): (Map<String, Any?>) -> ValidateOutcome {
        val assertName = obj["assert"]!!.jsonPrimitive.content
        TransformRegistry.validateAssertionName(assertName)
        val onFail = obj["onFail"]?.jsonPrimitive?.content ?: "skip"

        return { parsed ->
            val outcome = TransformRegistry.validate(assertName, obj, parsed)
            when (outcome) {
                is ValidateOutcome.Pass -> ValidateOutcome.Pass
                else -> when (onFail) {
                    "skip" -> ValidateOutcome.Skip
                    "dropParsed" -> ValidateOutcome.DropParsed
                    else -> ValidateOutcome.Skip
                }
            }
        }
    }

    // ==========================================================================
    //  Action entry compiler (ADR-0006)
    // ==========================================================================

    private fun compileActionEntry(obj: JsonObject): CompiledAction {
        return CompiledAction(
            verb = obj["command"]!!.jsonPrimitive.content,
            targetBindName = obj["target"]!!.jsonPrimitive.content.removePrefix("$"),
            onlyIf = obj["onlyIf"]?.jsonObject?.let { compileGate(it) },
            dedupeKey = obj["dedupeKey"]?.jsonPrimitive?.content,
            throttleMs = obj["throttleMs"]?.jsonPrimitive?.longOrNull,
        )
    }

    private fun compileGate(obj: JsonObject): cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate {
        obj["fieldEquals"]?.jsonObject?.let { fe ->
            val field = fe["field"]!!.jsonPrimitive.content
            val value = parseGateValue(fe["value"])
            return cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate.FieldEquals(field, value)
        }
        obj["fieldNotEquals"]?.jsonObject?.let { fe ->
            val field = fe["field"]!!.jsonPrimitive.content
            val value = parseGateValue(fe["value"])
            return cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate.FieldNotEquals(field, value)
        }
        obj["fieldNotNull"]?.jsonObject?.let { fn ->
            val field = fn["field"]!!.jsonPrimitive.content
            return cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate.FieldNotNull(field)
        }
        throw RuleCompileException("Unknown gate type in onlyIf: ${obj.keys}")
    }

    private fun parseGateValue(element: JsonElement?): Any? {
        if (element == null) return null
        val prim = element.jsonPrimitive
        return prim.booleanOrNull
            ?: prim.longOrNull
            ?: prim.doubleOrNull
            ?: prim.content
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
        val intent = obj["intent"]?.jsonPrimitive?.content
            ?: obj["target"]?.jsonPrimitive?.content?.let { camelToSnake(it) }
            ?: deriveTargetFromId(id)
        val condition = compileNodePred(obj["if"]!!)
        val (flow, modeHint) = parseStateBlock(obj["state"] as? JsonObject, id)
        val intentFactory: (UiNode) -> String = { _ -> intent }
        val screenConstraint = obj["screenIs"]?.jsonPrimitive?.content

        return CompiledClickRule(id, priority, overrideable, condition, intentFactory, flow, modeHint, screenConstraint)
    }

    /** Convert CamelCase to snake_case. "AcceptOffer" → "accept_offer" */
    private fun camelToSnake(s: String): String =
        s.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

    /**
     * Derive a classification name from a rule ID by stripping the platform prefix.
     * "doordash.screen.idle_map" → "idle_map"
     * "doordash.click.accept_offer" → "accept_offer"
     * "doordash.notification.additional_tip" → "additional_tip"
     */
    private fun deriveTargetFromId(id: String): String {
        // Strip "{platform}.{type}." prefix — take everything after the second dot
        val parts = id.split(".", limit = 3)
        return if (parts.size >= 3) parts[2] else id
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
        val shape = obj["shape"]?.jsonPrimitive?.content
        val intent = obj["intent"]?.jsonPrimitive?.content
            ?: obj["target"]?.jsonPrimitive?.content?.let { camelToSnake(it) }
            ?: deriveTargetFromId(id)
        val ifJson = obj["if"]
        val extract = obj["extract"] as? JsonObject
        val (flow, modeHint) = parseStateBlock(obj["state"] as? JsonObject, id)

        val classify: (RawNotificationData) -> NotificationClassifyResult? = when {
            intent == "additional_tip" && extract != null ->
                compileAdditionalTipRule(ifJson!!, intent)

            else -> {
                val pred = ifJson?.let { compileNotifPred(it) }
                ;{ raw ->
                    if (pred == null || pred(raw)) NotificationClassifyResult(intent)
                    else null
                }
            }
        }

        return CompiledNotificationRule(id, priority, overrideable, classify, shape, flow, modeHint)
    }

    private fun compileAdditionalTipRule(
        ifJson: JsonElement,
        intent: String,
    ): (RawNotificationData) -> NotificationClassifyResult? {
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
                NotificationClassifyResult(
                    intent = intent,
                    fields = mapOf(
                        "amount" to amount,
                        "storeName" to storeName,
                        "deliveredAt" to deliveredAt,
                    ),
                )
            }
        }
    }

    // ==========================================================================
    //  Tree predicate compiler (operates on full UiNode tree)
    // ==========================================================================

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
    //  Node predicate compiler (operates on a single UiNode)
    // ==========================================================================

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

            // --- Boolean flag predicates ---
            "isClickable" -> { node -> node.isClickable }
            "isEnabled" -> { node -> node.isEnabled }
            "isChecked" -> { node -> node.isChecked != 0 }
            "hasChildren" -> { node -> node.children.isNotEmpty() }
            "isLeaf" -> { node -> node.children.isEmpty() }

            // --- Logical combinators ---
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
            "isOngoing" ->
                { raw -> raw.isOngoing }
            "channelIdEquals" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.channelId == s }
            }
            "channelIdContains" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.channelId?.contains(s, ignoreCase = true) == true }
            }
            "categoryEquals" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.category == s }
            }
            "hasAction" -> {
                val s = (value as JsonPrimitive).content
                ;{ raw -> raw.actionLabels.any { it.contains(s, ignoreCase = true) } }
            }
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
    //  State block parser (ADR-0005)
    // ==========================================================================

    fun parseStateBlock(stateObj: JsonObject?, ruleId: String): Pair<Flow?, Mode?> {
        if (stateObj == null) return null to null

        val flowStr = stateObj["flow"]?.jsonPrimitive?.content
        val modeStr = stateObj["modeHint"]?.jsonPrimitive?.content
            ?: stateObj["mode"]?.jsonPrimitive?.content

        val flow = flowStr?.let {
            Flow.fromWire(it)
                ?: throw RuleCompileException("Rule '$ruleId': unknown flow value '$it'")
        }
        val modeHint = modeStr?.let {
            Mode.fromWire(it)
                ?: throw RuleCompileException("Rule '$ruleId': unknown mode value '$it'")
        }
        return flow to modeHint
    }

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    /** Read a named property from a UiNode. */
    private fun readProperty(node: UiNode, prop: String): String? = when (prop) {
        "text" -> node.text
        "allText" -> node.allText.joinToString("")
        "contentDescription" -> node.contentDescription
        "stateDescription" -> node.stateDescription
        "viewIdResourceName" -> node.viewIdResourceName
        "className" -> node.className
        else -> throw RuleCompileException("Unknown read property: '$prop'")
    }

    /** Apply a navigation instruction to a node. */
    private fun applyNavigation(node: UiNode, navSpec: JsonElement): UiNode? {
        val nav = navSpec.jsonPrimitive.content
        return when {
            nav == "parent" -> node.parent
            nav.startsWith("ancestor(") -> {
                val n = nav.removePrefix("ancestor(").removeSuffix(")").toIntOrNull() ?: 1
                node.ancestor(n)
            }
            nav.startsWith("sibling(") -> {
                val offset = nav.removePrefix("sibling(").removeSuffix(")").toIntOrNull() ?: 1
                node.sibling(offset)
            }
            nav.startsWith("findChild(") -> {
                val idSuffix = nav.removePrefix("findChild(").removeSuffix(")")
                node.findChildById(idSuffix)
            }
            nav.startsWith("findDescendant(") -> {
                val idSuffix = nav.removePrefix("findDescendant(").removeSuffix(")")
                node.findDescendantById(idSuffix)
            }
            else -> throw RuleCompileException("Unknown navigation: '$nav'")
        }
    }

    /** Resolve a JSON literal to a Kotlin value. */
    private fun resolveLiteral(json: JsonElement): Any? = when (json) {
        is JsonPrimitive -> when {
            json.booleanOrNull != null -> json.booleanOrNull
            json.intOrNull != null -> json.intOrNull
            json.longOrNull != null -> json.longOrNull
            json.isString -> json.content
            else -> json.content
        }
        else -> json.toString()
    }

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
