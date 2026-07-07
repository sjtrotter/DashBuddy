package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Compiles screen (`UiNode`) and notification `parse:`/`validate:` blocks
 * into typed field extractors (#239 extraction from `RuleCompiler`, pure
 * move — no behavior change). [RuleCompiler] keeps no public forwards here —
 * every entry point ([compileScreenParseBlock], [compileNotificationParseBlock],
 * [compileValidateEntry]) is called only from `RuleCompiler.compileBranch`,
 * which is why they stay package-`internal` rather than gaining a facade
 * delegate (no external caller referenced them directly before this move —
 * verified against every `RuleCompiler.*` call site in the repo).
 */
internal object ParseExpressionCompiler {

    /** Maximum elements harvested by an `each`/`findAll` expression (#590 DoS
     *  bound) — moved from `RuleCompiler.MAX_EACH_SIZE`; scoped to parse-expression
     *  compiling, its only consumer. */
    private const val MAX_EACH_SIZE = 50

    // ==========================================================================
    //  Screen parse block compiler
    // ==========================================================================

    /**
     * Compile a `parse:` block for screen rules.
     * Operates on UiNode tree with optional bindings.
     */
    fun compileScreenParseBlock(parseObj: JsonObject): (UiNode, Bindings) -> Map<String, Any?> {
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

    // ==========================================================================
    //  Notification parse block compiler
    // ==========================================================================

    /**
     * Compile a `parse:` block for notification rules.
     * Operates on RawNotificationData string fields.
     *
     * Supported parse expressions for notifications:
     * - `from:` — selects a notification field (title, text, bigText, tickerText, subText, fullString)
     * - `find:` — regex match on the selected field
     * - `group:` — capture group index (default 0 = whole match)
     * - `transform:` — apply transforms from TransformRegistry
     * - `literal:` — constant value
     * - `fallback:` — default if extraction returns null
     */
    fun compileNotificationParseBlock(
        parseObj: JsonObject,
    ): (RawNotificationData) -> Map<String, Any?> {
        val fieldsObj = parseObj["fields"]?.jsonObject ?: return { emptyMap() }

        data class NotifFieldExtractor(
            val name: String,
            val extract: (RawNotificationData) -> Any?,
        )

        val extractors = fieldsObj.entries.map { (fieldName, fieldSpec) ->
            NotifFieldExtractor(fieldName, compileNotifParseExpression(fieldSpec))
        }

        return { raw ->
            val result = mutableMapOf<String, Any?>()
            for (extractor in extractors) {
                result[extractor.name] = extractor.extract(raw)
            }
            result
        }
    }

    /**
     * Compile a single notification parse expression.
     */
    private fun compileNotifParseExpression(spec: JsonElement): (RawNotificationData) -> Any? {
        if (spec is JsonPrimitive) {
            val value = spec.content
            return { _ -> value }
        }

        val obj = spec as? JsonObject
            ?: throw RuleCompileException("Notification parse expression must be an object or string")

        // Literal
        if ("literal" in obj) {
            val value = resolveLiteral(obj["literal"]!!)
            return { _ -> value }
        }

        // From + find pattern
        val fromField = obj["from"]?.jsonPrimitive?.content
            ?: throw RuleCompileException(
                "Notification parse expression requires 'from' or 'literal'. Keys: ${obj.keys}",
            )
        val findPattern = obj["find"]?.jsonPrimitive?.content
        val group = obj["group"]?.jsonPrimitive?.intOrNull ?: 0
        val transformSpec = obj["transform"]
        val fallbackVal = obj["fallback"]

        // Validate transform specs at compile time (fail fast on typos)
        transformSpec?.let { TransformRegistry.validateTransformSpec(it) }
        // Compile the find regex ONCE (#362) — it used to recompile per
        // notification, per field, violating the no-hot-path-parsing contract.
        val findRegex = findPattern?.let { compileRegex(it) }

        return { raw ->
            val sourceText = readNotificationField(raw, fromField)

            val rawValue = if (findRegex != null && sourceText != null) {
                findRegex.find(sourceText)?.groupValues?.getOrNull(group)
            } else {
                sourceText
            }

            val transformed = if (rawValue != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, rawValue)
            } else rawValue

            transformed ?: fallbackVal?.let { resolveLiteral(it) }
        }
    }

    /**
     * Read a named field from a notification.
     */
    private fun readNotificationField(raw: RawNotificationData, field: String): String? = when (field) {
        "title" -> raw.title
        "text" -> raw.text
        "bigText" -> raw.bigText
        "tickerText" -> raw.tickerText
        "subText" -> raw.subText
        "fullString" -> raw.toFullString()
        else -> throw RuleCompileException("Unknown notification field: '$field'")
    }

    // ==========================================================================
    //  Screen parse expression compiler
    // ==========================================================================

    /**
     * Compile a single parse expression for screen rules. [depth] bounds the
     * `each`/`join`/`coalesce` recursion at [RuleCompiler.MAX_PARSE_DEPTH]
     * (#590) — a fail-closed [RuleCompileException] in place of a stack-overflowing
     * crash.
     */
    private fun compileParseExpression(
        spec: JsonElement,
        depth: Int = 0,
    ): (UiNode, Bindings) -> Any? {
        if (depth > RuleCompiler.MAX_PARSE_DEPTH)
            throw RuleCompileException(
                "Parse expression nesting exceeds MAX_PARSE_DEPTH=${RuleCompiler.MAX_PARSE_DEPTH}",
            )

        if (spec is JsonPrimitive) {
            val value = spec.content
            return { _, _ -> value }
        }

        val obj = spec as? JsonObject
            ?: throw RuleCompileException("Parse expression must be an object or string literal")

        // Validate transform specs at compile time (fail fast on typos)
        obj["transform"]?.let { TransformRegistry.validateTransformSpec(it) }

        val fromName = obj["from"]?.jsonPrimitive?.content?.removePrefix("$")

        return when {
            "literal" in obj -> compileLiteral(obj)
            "find" in obj -> compileFind(obj, fromName)
            "findAll" in obj -> compileFindAll(obj, fromName)
            "textAfterLabel" in obj -> compileTextAfterLabel(obj, fromName)
            "presence" in obj -> compilePresence(obj, fromName)
            "conditionalEnum" in obj -> compileConditionalEnum(obj, fromName)
            "each" in obj -> compileEach(obj, fromName, depth)
            "join" in obj -> compileJoin(obj, depth)
            "coalesce" in obj -> compileCoalesce(obj, depth)
            "siblingOf" in obj -> compileSiblingOf(obj, fromName)
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

    private fun compileFind(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["find"]!!)
        val readProp = obj["read"]?.jsonPrimitive?.content
        val transformSpec = obj["transform"]
        val fallbackVal = obj["fallback"]
        val rejectIfSpec = obj["rejectIf"]?.let { compileNodePred(it) }

        // Parse navigation ONCE at compile time (#362) — unknown navigation
        // verbs now fail rule loading, not the first live match.
        val navFn = obj["navigate"]?.let { compileNavigation(it) }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            var foundNode = startNode.findNode(findPred)

            if (foundNode != null && navFn != null) {
                foundNode = navFn(foundNode)
            }

            if (foundNode != null && rejectIfSpec != null && rejectIfSpec(foundNode)) {
                foundNode = null
            }

            val rawValue = if (foundNode != null && readProp != null) {
                readProperty(foundNode, readProp)
            } else if (foundNode != null && readProp == null) {
                foundNode.text
            } else null

            val transformed = if (rawValue != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, rawValue)
            } else rawValue

            transformed ?: fallbackVal?.let { resolveLiteral(it) }
        }
    }

    private fun compileSiblingOf(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["siblingOf"]!!)
        val offset = obj["offset"]?.jsonPrimitive?.intOrNull ?: 1
        val readProp = obj["read"]?.jsonPrimitive?.content ?: "text"
        val transformSpec = obj["transform"]

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            val labelNode = startNode.findNode(findPred)
            val siblingNode = labelNode?.sibling(offset)

            val rawValue = if (siblingNode != null) {
                readProperty(siblingNode, readProp)
            } else null

            if (rawValue != null && transformSpec != null) {
                TransformRegistry.applyAny(transformSpec, rawValue)
            } else rawValue
        }
    }

    private fun compileFindAll(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["findAll"]!!)
        val readProp = obj["read"]?.jsonPrimitive?.content ?: "text"

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            val nodes = startNode.findNodes(findPred).take(MAX_EACH_SIZE)
            nodes.mapNotNull { readProperty(it, readProp) }
        }
    }

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

    private fun compilePresence(obj: JsonObject, fromName: String?): (UiNode, Bindings) -> Any? {
        val presenceSpec = obj["presence"]!!.jsonObject
        val check = when {
            "exists" in presenceSpec -> {
                val nodePred = compileNodePred(presenceSpec["exists"]!!)
                val fn: (UiNode) -> Boolean = { tree -> tree.findNode(nodePred) != null }
                fn
            }
            "allTextContains" in presenceSpec -> {
                val text = presenceSpec["allTextContains"]!!.jsonPrimitive.content
                    .lowercase(java.util.Locale.ROOT)
                val fn: (UiNode) -> Boolean = { tree ->
                    tree.allTextLowerJoined.contains(text)
                }
                fn
            }
            else -> {
                val pred = compileTreePred(JsonObject(presenceSpec))
                pred
            }
        }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            check(startNode)
        }
    }

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

    private fun compileEach(obj: JsonObject, fromName: String?, depth: Int = 0): (UiNode, Bindings) -> Any? {
        val findPred = compileNodePred(obj["each"]!!)
        val scopeSpec = obj["scope"]
        val excludeSpec = obj["exclude"]?.let { compileNodePred(it) }
        val extractObj = obj["extract"]?.jsonObject
            ?: throw RuleCompileException("'each' requires 'extract' block")

        val compiledExtract: List<Pair<String, (UiNode, Bindings) -> Any?>> =
            extractObj.entries.map { (name, spec) ->
                name to compileParseExpression(spec, depth + 1)
            }

        return { tree, bindings ->
            val startNode = if (fromName != null) bindings[fromName] ?: tree else tree
            val rawNodes = startNode.findNodes(findPred).take(MAX_EACH_SIZE)

            val scopedNodes = if (scopeSpec != null) {
                val ancestorN = scopeSpec.jsonPrimitive.content
                    .removePrefix("ancestor(").removeSuffix(")").toIntOrNull() ?: 1
                rawNodes.mapNotNull { it.ancestor(ancestorN) }.distinct()
            } else rawNodes

            val filtered = if (excludeSpec != null) {
                scopedNodes.filter { !excludeSpec(it) }
            } else scopedNodes

            filtered.map { itemNode ->
                val itemFields = mutableMapOf<String, Any?>()
                for ((name, expr) in compiledExtract) {
                    itemFields[name] = expr(itemNode, bindings)
                }
                itemFields
            }
        }
    }

    private fun compileJoin(obj: JsonObject, depth: Int = 0): (UiNode, Bindings) -> Any? {
        val partsArray = obj["join"]!!.jsonArray
        val separator = obj["separator"]?.jsonPrimitive?.content ?: ""
        val skipNulls = obj["skipNulls"]?.jsonPrimitive?.booleanOrNull ?: true
        val transformSpec = obj["transform"]

        val compiledParts = partsArray.map { compileParseExpression(it, depth + 1) }

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

    private fun compileCoalesce(obj: JsonObject, depth: Int = 0): (UiNode, Bindings) -> Any? {
        // #549 hardening: coalesce returns the first non-null branch verbatim — it does NOT apply a
        // top-level transform. Silently dropping one would be a privacy footgun: a PII coalesce whose
        // sha256 sat at the top level (instead of inside each branch) would emit the RAW value. Reject
        // it at compile time so the transform must live in each branch (where it actually runs).
        if ("transform" in obj) {
            throw RuleCompileException(
                "coalesce does not apply a top-level 'transform' — put the transform inside each " +
                    "branch (a top-level transform would be silently dropped, which for a PII field " +
                    "would leak the raw value)",
            )
        }
        val exprs = obj["coalesce"]!!.jsonArray.map { compileParseExpression(it, depth + 1) }

        return { tree, bindings ->
            exprs.firstNotNullOfOrNull { it(tree, bindings) }
        }
    }

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

    fun compileValidateEntry(obj: JsonObject): (Map<String, Any?>) -> ValidateOutcome {
        val assertName = obj["assert"]!!.jsonPrimitive.content
        ValidateRegistry.validateAssertionName(assertName)
        val onFail = obj["onFail"]?.jsonPrimitive?.content ?: "skip"
        // A typo'd onFail used to coerce silently to "skip" (#362).
        if (onFail !in setOf("skip", "dropParsed")) {
            throw RuleCompileException(
                "Unknown onFail: '$onFail' (expected 'skip' or 'dropParsed')",
                isolable = true, // authoring typo — the rule isolates (#293 item 4)
            )
        }

        return { parsed ->
            val outcome = ValidateRegistry.validate(assertName, obj, parsed)
            when (outcome) {
                is ValidateOutcome.Pass -> ValidateOutcome.Pass
                else -> if (onFail == "dropParsed") ValidateOutcome.DropParsed else ValidateOutcome.Skip
            }
        }
    }

    // -- delegated predicate compiling (kept in PredicateCompiler; called here
    //    for 'find'/'presence'/'conditionalEnum' expressions) --------------------

    private fun compileNodePred(json: JsonElement) = PredicateCompiler.compileNodePred(json)
    private fun compileTreePred(json: JsonElement) = PredicateCompiler.compileTreePred(json)
}
