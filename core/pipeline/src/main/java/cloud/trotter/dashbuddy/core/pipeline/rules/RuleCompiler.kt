package cloud.trotter.dashbuddy.core.pipeline.rules

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
 * All rule types (screen, click, notification) compile through [compileRules]
 * with a [RuleContext] that determines predicate vocabulary and parse input source.
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
    //  Permission introspection
    // ==========================================================================

    /**
     * Scan compiled rules and return the set of [PermissionTier] values
     * used by any effect verb. Used at rule-load time to determine what
     * permissions a ruleset requires.
     */
    fun enumeratePermissions(
        rules: List<CompiledRule<*>>,
    ): Set<cloud.trotter.dashbuddy.domain.pipeline.PermissionTier> {
        val tiers = mutableSetOf<cloud.trotter.dashbuddy.domain.pipeline.PermissionTier>()
        for (rule in rules) {
            for (branch in rule.branches) {
                for (effect in branch.effects) {
                    tiers.add(effect.verb.tier)
                }
                for ((_, overrideEffects) in branch.transitionOverrides) {
                    for (effect in overrideEffects) {
                        tiers.add(effect.verb.tier)
                    }
                }
            }
        }
        tiers.remove(cloud.trotter.dashbuddy.domain.pipeline.PermissionTier.NONE)
        return tiers
    }

    /**
     * Enumerate the app-owned actions a compiled ruleset's bindings enable
     * (#422, refit by #425) — one [RuleCapability] per (rule, action) whose
     * well-known target bind name (`RuleAction.targetBindName`) the rule
     * binds. The unit of user consent. Deduped by [RuleCapability.key] (the
     * same binding compiled into several branches is one capability).
     * [source] is recorded for provenance only — consent is uniform
     * regardless of where the rule came from.
     *
     * The key hashes `(ruleId, action, the CANONICAL binding definition)` —
     * pinned to the predicate that selects the node, so repointing the
     * binding (even with the same bind name) forces re-consent. The future
     * execution gate (#417) looks grants up from this enumeration at fire
     * time; nothing is threaded through the effect pipeline.
     */
    fun enumerateCapabilities(
        rules: List<CompiledRule<*>>,
        source: String,
    ): List<cloud.trotter.dashbuddy.domain.capability.RuleCapability> =
        CapabilityEnumerator.enumerate(rules, source)

    // ==========================================================================
    //  Unified rule compilation
    // ==========================================================================

    /**
     * Compile a JSON array of rule objects into typed [CompiledRule] instances.
     * The [context] determines predicate vocabulary and parse input source.
     */
    fun <TInput> compileRules(array: JsonArray, context: RuleContext): List<CompiledRule<TInput>> {
        val rules = array.map { compileRule<TInput>(it.jsonObject, context) }
        // Enforce unique priorities within the same rule type
        val seen = mutableMapOf<Int, String>()
        for (rule in rules) {
            val existing = seen.put(rule.priority, rule.id)
            if (existing != null) {
                throw RuleCompileException(
                    "Duplicate priority ${rule.priority} in ${context.name.lowercase()} rules: " +
                        "'$existing' and '${rule.id}'. Priorities must be unique per rule type.",
                )
            }
        }
        return rules
    }

    @Suppress("UNCHECKED_CAST")
    private fun <TInput> compileRule(obj: JsonObject, context: RuleContext): CompiledRule<TInput> {
        val id = obj["id"]!!.jsonPrimitive.content
        val priority = obj["priority"]!!.jsonPrimitive.int
        val overrideable = obj["overrideable"]?.jsonPrimitive?.booleanOrNull ?: true

        // Rule-level bindings (screen rules only)
        val ruleBindObj = obj["bind"]?.jsonObject
        val ruleBindings = if (context == RuleContext.SCREEN && ruleBindObj != null) {
            compileBindBlock(ruleBindObj)
        } else emptyList()

        val ruleState = parseStateBlock(obj["state"] as? JsonObject, id)

        // Rule-level parse (inherited by branches unless overridden)
        val ruleParseObj = obj["parse"]?.jsonObject
        val ruleParseAs = ruleParseObj?.get("as")?.jsonPrimitive?.content

        // Rule-level redact directives. Screen rules use a `redact` ARRAY of node
        // predicates (#598); notification rules use a `redact` OBJECT keyed by
        // field (#620). Compiled into distinct carriers so neither context can
        // read the other's shape.
        val redact = if (context == RuleContext.SCREEN) {
            obj["redact"]?.jsonArray?.let { compileRedactBlock(it) } ?: CompiledRedact.EMPTY
        } else CompiledRedact.EMPTY
        val notifRedact = if (context == RuleContext.NOTIFICATION) {
            obj["redact"]?.jsonObject?.let { compileNotifRedactBlock(it) } ?: CompiledNotifRedact.EMPTY
        } else CompiledNotifRedact.EMPTY

        // #598 fail-closed: a screen rule that hashes customer PII in its parse
        // (`sha256` transform) MUST declare a non-empty `redact` block. The hash
        // side (parse output) and the disk side (envelope) can't drift — a rule
        // that hashes a name but ships the raw text in the capture is the exact
        // #598 bug. Scoped to SCREEN so a notification sha256 doesn't trip it.
        if (context == RuleContext.SCREEN && redact.isEmpty() && jsonUsesSha256(obj)) {
            throw RuleCompileException(
                "Screen rule '$id' uses the sha256 transform but declares no 'redact' block " +
                    "(#598): a rule that hashes customer PII in its parse must redact the same raw " +
                    "nodes from the capture envelope, or the plaintext ships to disk. Add a " +
                    "top-level \"redact\": [ { \"find\": <nodePred>, \"keepPrefix\": [ ... ] } ].",
            )
        }

        val branches: List<CompiledBranch<TInput>> = if ("branches" in obj) {
            obj["branches"]!!.jsonArray.map { branchElement ->
                val branchObj = branchElement.jsonObject
                // #624 (VET V3): `redact` is a WHOLE-RULE directive — compileBranch
                // never reads it, so a `redact` inside a branches[] entry would
                // silently no-op and a rule author would believe their branch masks
                // capture PII when it does not. Reject at compile time. This is
                // enforced ONLY for branches[] entries, NOT the branchless whole-rule
                // compileBranch call below, which legitimately carries top-level redact.
                if ("redact" in branchObj) {
                    throw RuleCompileException(
                        "Rule '$id': a `redact` block inside a branches[] entry is not supported — " +
                            "redact masks capture-envelope nodes for the ENTIRE recognized frame, " +
                            "not per-branch. Move it to the rule's top level.",
                    )
                }
                compileBranch(branchObj, context, ruleState.flow, ruleState.modeHint,
                    ruleOutcomes = ruleState.outcomes, ruleId = id,
                    ruleParseBlock = ruleParseObj, ruleParseAs = ruleParseAs, ruleBindObj = ruleBindObj)
            }
        } else {
            listOf(compileBranch(obj, context, ruleState.flow, ruleState.modeHint,
                ruleOutcomes = ruleState.outcomes, ruleId = id))
        }

        return CompiledRule(id, priority, overrideable, ruleBindings, branches, redact, notifRedact)
    }

    /**
     * Compile a notification rule's `redact` OBJECT (#620), keyed by field name
     * (title/text/bigText/tickerText/subText). Each value is either a whole-field
     * spec (optional `keepPrefix`) or a regex-capture spec (`match` + `maskGroup`,
     * default group 1). Regexes go through the bounded [compileRegex]/[RegexSafety]
     * guard — notification rules are untrusted input on the #192 CDN path too.
     */
    private fun compileNotifRedactBlock(obj: JsonObject): CompiledNotifRedact {
        val fields = obj.entries.associate { (fieldName, spec) ->
            val field = NotifField.fromWire(fieldName)
                ?: throw RuleCompileException(
                    "notification redact: unknown field '$fieldName' " +
                        "(expected one of ${NotifField.entries.map { it.wire }})",
                )
            val specObj = spec as? JsonObject
                ?: throw RuleCompileException(
                    "notification redact: field '$fieldName' must be an object " +
                        "({ \"keepPrefix\": [...] } or { \"match\": <regex>, \"maskGroup\": <int> })",
                )
            val matchPattern = specObj["match"]?.jsonPrimitive?.content
            val masker: NotifFieldMask = if (matchPattern != null) {
                val regex = compileRegex(matchPattern)
                val group = specObj["maskGroup"]?.jsonPrimitive?.intOrNull ?: 1
                NotifFieldMask.RegexGroup(regex, group)
            } else {
                val keepPrefix = specObj["keepPrefix"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                NotifFieldMask.Whole(keepPrefix)
            }
            field to masker
        }
        return CompiledNotifRedact(fields)
    }

    // ==========================================================================
    //  Redact block compiler (#598)
    // ==========================================================================

    /**
     * Compile a rule's top-level `redact` array. Each entry is an OBJECT:
     * `{ "find": <nodePred>, "keepPrefix": [ ... ]? }`. The predicate reuses the
     * exact node-predicate vocabulary of `require`/`bind`; masking happens at
     * capture time (see [CompiledRedact]).
     */
    private fun compileRedactBlock(array: JsonArray): CompiledRedact {
        val entries = array.map { element ->
            val obj = element as? JsonObject
                ?: throw RuleCompileException("redact entry must be an object with a 'find' predicate")
            val findSpec = obj["find"]
                ?: throw RuleCompileException("redact entry has no 'find' predicate")
            val find = compileNodePred(findSpec)
            val keepPrefix = obj["keepPrefix"]?.jsonArray?.map { it.jsonPrimitive.content }
                ?: emptyList()
            CompiledRedactEntry(find, keepPrefix)
        }
        return CompiledRedact(entries)
    }

    /** Recursively scan raw rule JSON for a `"sha256"` transform value (#598). */
    private fun jsonUsesSha256(element: JsonElement): Boolean = when (element) {
        is JsonPrimitive -> element.isString && element.content == "sha256"
        is JsonObject -> element.values.any { jsonUsesSha256(it) }
        is JsonArray -> element.any { jsonUsesSha256(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <TInput> compileBranch(
        obj: JsonObject,
        context: RuleContext,
        ruleFlow: Flow? = null,
        ruleModeHint: Mode? = null,
        ruleOutcomes: Set<Flow>? = null,
        ruleId: String? = null,
        ruleParseBlock: JsonObject? = null,
        ruleParseAs: String? = null,
        ruleBindObj: JsonObject? = null,
    ): CompiledBranch<TInput> {
        val targetName = ruleId?.let { deriveTargetFromId(it) }
            ?: throw RuleCompileException("Branch has no rule id to derive target from")

        // Branch-level state overrides rule-level defaults
        val branchState = parseStateBlock(
            obj["state"] as? JsonObject, "$targetName-branch",
        )

        // Intent (explicit or derived)
        val intent = obj["intent"]?.jsonPrimitive?.content ?: targetName

        // --- Bindings (screen rules only) ---
        val effectiveBindObj = if (context == RuleContext.SCREEN) {
            obj["bind"]?.jsonObject ?: ruleBindObj
        } else null
        val bindings = effectiveBindObj?.let { compileBindBlock(it) } ?: emptyList()

        // --- Phase 2: Reject ---
        val rejectJson = obj["reject"]
        val rejectChecks: List<(TInput) -> Boolean> = rejectJson?.jsonArray?.map { rejectEntry ->
            compilePredicate(rejectEntry, context)
        } ?: emptyList()

        // --- Phase 3: Require ---
        val requireJson = obj["require"]
        val predicate: ((TInput) -> Boolean)? = requireJson?.let {
            compilePredicate(it, context)
        }

        // --- Phase 4: Parse ---
        val parseBlock = obj["parse"]?.jsonObject ?: ruleParseBlock
        val parseAs = parseBlock?.get("as")?.jsonPrimitive?.content ?: ruleParseAs

        // --- Shape contract validation (M3) ---
        if (parseAs != null) {
            val declaredFields = parseBlock?.get("fields")?.jsonObject?.keys ?: emptySet()
            ParsedFieldsFactory.validateShapeFields(parseAs, declaredFields, ruleId)
        }

        val parser: (TInput, Bindings) -> Map<String, Any?> = when (context) {
            RuleContext.SCREEN -> {
                if (parseBlock != null) {
                    val compiled = compileScreenParseBlock(parseBlock)
                    val fn: (TInput, Bindings) -> Map<String, Any?> = { input, bindings ->
                        compiled(input as UiNode, bindings)
                    }
                    fn
                } else {
                    { _, _ -> emptyMap() }
                }
            }
            RuleContext.NOTIFICATION -> {
                if (parseBlock != null) {
                    val compiled = compileNotificationParseBlock(parseBlock)
                    val fn: (TInput, Bindings) -> Map<String, Any?> = { input, _ ->
                        compiled(input as RawNotificationData)
                    }
                    fn
                } else {
                    { _, _ -> emptyMap() }
                }
            }
            RuleContext.CLICK -> {
                // Click rules currently don't have parse blocks
                { _, _ -> emptyMap() }
            }
        }

        // --- Phase 5: Validate ---
        val validators = obj["validate"]?.jsonArray?.map { entry ->
            compileValidateEntry(entry.jsonObject)
        } ?: emptyList()

        // --- Effects (observational/app-internal only; actuation rejected in compileEffectEntry, #425) ---
        val effects = obj["effects"]?.jsonArray?.map { compileEffectEntry(it.jsonObject) } ?: emptyList()

        // --- Transition overrides (screen rules) ---
        val transitionOverrides = obj["transitionOverrides"]?.jsonObject?.let {
            compileTransitionOverrides(it)
        } ?: emptyMap()

        // --- Click-specific: screenIs ---
        val screenIs = obj["screenIs"]?.jsonPrimitive?.content

        return CompiledBranch(
            predicate = predicate,
            rejectChecks = rejectChecks,
            parser = parser,
            validators = validators,
            effects = effects,
            bindings = bindings,
            shape = parseAs,
            intent = intent,
            flow = branchState.flow ?: ruleFlow,
            modeHint = branchState.modeHint ?: ruleModeHint,
            outcomes = branchState.outcomes ?: ruleOutcomes,
            screenIs = screenIs,
            transitionOverrides = transitionOverrides,
        )
    }

    // ==========================================================================
    //  Context-dispatched predicate compilation
    // ==========================================================================

    /**
     * Compile a predicate JSON element into a type-safe lambda.
     * The [context] determines which predicate vocabulary is used.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <TInput> compilePredicate(
        json: JsonElement,
        context: RuleContext,
    ): (TInput) -> Boolean = when (context) {
        RuleContext.SCREEN -> {
            val pred = compileTreePred(json)
            pred as (TInput) -> Boolean
        }
        RuleContext.CLICK -> {
            val pred = compileNodePred(json)
            pred as (TInput) -> Boolean
        }
        RuleContext.NOTIFICATION -> {
            val pred = compileNotifPred(json)
            pred as (TInput) -> Boolean
        }
    }

    // ==========================================================================
    //  Bind block compiler
    // ==========================================================================

    private fun compileBindBlock(obj: JsonObject): List<Binding> = obj.entries.map { (name, spec) ->
        val specObj = spec.jsonObject
        val optional = specObj["optional"]?.jsonPrimitive?.booleanOrNull ?: false
        val findSpec = specObj["find"] ?: throw RuleCompileException("Binding '$name' has no 'find'")
        val nodePred = compileNodePred(findSpec)
        val find: (UiNode) -> UiNode? = { tree -> tree.findNode(nodePred) }
        // Raw definition retained for capability-key pinning (#422/#425).
        Binding(name, find, optional, defJson = specObj)
    }

    // ==========================================================================
    //  Screen parse block compiler
    // ==========================================================================

    /**
     * Compile a `parse:` block for screen rules.
     * Operates on UiNode tree with optional bindings.
     */
    private fun compileScreenParseBlock(parseObj: JsonObject): (UiNode, Bindings) -> Map<String, Any?> {
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
    private fun compileNotificationParseBlock(
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
     * Compile a single parse expression for screen rules.
     */
    private fun compileParseExpression(spec: JsonElement): (UiNode, Bindings) -> Any? {
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
            "each" in obj -> compileEach(obj, fromName)
            "join" in obj -> compileJoin(obj)
            "coalesce" in obj -> compileCoalesce(obj)
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
                val text = presenceSpec["allTextContains"]!!.jsonPrimitive.content.lowercase()
                val fn: (UiNode) -> Boolean = { tree ->
                    tree.allText.joinToString("\u001F").lowercase().contains(text)
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

    private fun compileCoalesce(obj: JsonObject): (UiNode, Bindings) -> Any? {
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
        val exprs = obj["coalesce"]!!.jsonArray.map { compileParseExpression(it) }

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

    private fun compileValidateEntry(obj: JsonObject): (Map<String, Any?>) -> ValidateOutcome {
        val assertName = obj["assert"]!!.jsonPrimitive.content
        ValidateRegistry.validateAssertionName(assertName)
        val onFail = obj["onFail"]?.jsonPrimitive?.content ?: "skip"
        // A typo'd onFail used to coerce silently to "skip" (#362).
        if (onFail !in setOf("skip", "dropParsed")) {
            throw RuleCompileException("Unknown onFail: '$onFail' (expected 'skip' or 'dropParsed')")
        }

        return { parsed ->
            val outcome = ValidateRegistry.validate(assertName, obj, parsed)
            when (outcome) {
                is ValidateOutcome.Pass -> ValidateOutcome.Pass
                else -> if (onFail == "dropParsed") ValidateOutcome.DropParsed else ValidateOutcome.Skip
            }
        }
    }

    // ==========================================================================
    //  Effect entry compiler
    // ==========================================================================

    private val allowedArgs: Map<cloud.trotter.dashbuddy.domain.pipeline.EffectVerb, Set<String>> = mapOf(
        // `category` names the evidence consent bucket the engine's #426 gate
        // checks against EvidenceConfig; a screenshot without one never fires.
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SCREENSHOT to setOf("prefix", "category"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.BUBBLE to setOf("text", "persona"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.LOG to setOf("type", "payload"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SPEAK to setOf("text", "platform"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SCHEDULE_TIMEOUT to setOf("type", "durationMs"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.CANCEL_TIMEOUT to setOf("type"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SESSION_START to setOf("platformName"),
        cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.SESSION_END to setOf("platformName"),
    )

    private val effectMetaKeys = setOf("onlyIf", "dedupeKey", "throttleMs")

    /**
     * Actuating verbs rules may NOT declare (#425). Rejected with a migration
     * pointer instead of the generic unknown-verb error: rules expose target
     * *bindings* and the app-owned `RuleAction` registry performs the tap.
     * Fail closed against future gesture wires too — none of these may ever
     * silently compile from untrusted rule JSON (#192).
     */
    private val rejectedActuationWires =
        setOf("click", "tap", "swipe", "scroll", "set_text", "long_click")

    internal fun compileEffectEntry(obj: JsonObject): CompiledEffect {
        val verbEntries = obj.entries.filter { it.key !in effectMetaKeys }
        if (verbEntries.isEmpty())
            throw RuleCompileException("Effect has no verb key")
        if (verbEntries.size > 1)
            throw RuleCompileException(
                "Effect has multiple verb keys: ${verbEntries.map { it.key }}",
            )

        val (wireVerb, verbValue) = verbEntries.single()
        if (wireVerb in rejectedActuationWires) {
            throw RuleCompileException(
                "Rule-declared '$wireVerb' effects were removed (#425): rules expose target " +
                    "bindings (e.g. bind: { \"declineButton\": { \"find\": ... } }) and the " +
                    "app-owned RuleAction registry performs taps. " +
                    "See docs/design/rule-capability-consent.md.",
            )
        }
        val verb = cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.fromWire(wireVerb)
            ?: throw RuleCompileException("Unknown effect verb: '$wireVerb'")

        val argsObj = verbValue.jsonObject
        val allowed = allowedArgs[verb] ?: emptySet()
        val argMap = mutableMapOf<String, String>()
        for ((key, value) in argsObj) {
            if (key !in allowed) {
                throw RuleCompileException(
                    "Unknown arg '$key' for verb '$wireVerb'. Allowed: $allowed",
                )
            }
            argMap[key] = value.jsonPrimitive.content
        }

        return CompiledEffect(
            verb = verb,
            args = argMap.toMap(),
            onlyIf = obj["onlyIf"]?.jsonObject?.let { compileGate(it) },
            dedupeKey = obj["dedupeKey"]?.jsonPrimitive?.content,
            throttleMs = obj["throttleMs"]?.jsonPrimitive?.longOrNull,
        )
    }

    internal fun compileTransitionOverrides(
        obj: JsonObject,
    ): Map<String, List<CompiledEffect>> {
        val result = mutableMapOf<String, List<CompiledEffect>>()
        for ((triggerWire, effectsElement) in obj) {
            cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger.fromWire(triggerWire)
                ?: throw RuleCompileException("Unknown transition trigger: '$triggerWire'")
            val effects = effectsElement.jsonArray.map { compileEffectEntry(it.jsonObject) }
            result[triggerWire] = effects
        }
        return result
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
    //  Helpers
    // ==========================================================================

    /**
     * THE id→name derivation (#404): strips the platform prefix.
     * "doordash.screen.idle_map" → "idle_map". Used for classification
     * targets here and intent fallbacks in [Ruleset] — one rule, one owner.
     */
    internal fun deriveTargetFromId(id: String): String {
        val parts = id.split(".", limit = 3)
        return if (parts.size >= 3) parts[2] else id
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
                ;{ tree -> tree.allText.joinToString("\u001F").lowercase().contains(text) }
            }
            "allTextContainsAll" -> {
                val texts = (value as? JsonArray)
                    ?.map { (it as JsonPrimitive).content.lowercase() }
                    ?: throw RuleCompileException("allTextContainsAll requires an array")
                ;{ tree ->
                    val joined = tree.allText.joinToString("\u001F").lowercase()
                    texts.all { joined.contains(it) }
                }
            }
            "allTextContainsAny" -> {
                val texts = (value as? JsonArray)
                    ?.map { (it as JsonPrimitive).content.lowercase() }
                    ?: throw RuleCompileException("allTextContainsAny requires an array")
                ;{ tree ->
                    val joined = tree.allText.joinToString("\u001F").lowercase()
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

            // Matches when *any* text node anywhere in this subtree (including
            // the node itself and any descendant's text or contentDescription)
            // equals the value, case-insensitive. Necessary for buttons whose
            // text lives in a child TextView (e.g. the DoorDash confirm-decline
            // Button has no text on the outer node; the text is in a nested
            // textView_prism_button_title child).
            "hasAnyText" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.allText.any { it.equals(s, ignoreCase = true) } }
            }

            "hasDesc" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.contentDescription?.equals(s, ignoreCase = true) == true }
            }
            "hasDescContaining" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.contentDescription?.contains(s, ignoreCase = true) == true }
            }

            "hasStateDescription" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.stateDescription?.equals(s, ignoreCase = true) == true }
            }
            "hasStateDescriptionContaining" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.stateDescription?.contains(s, ignoreCase = true) == true }
            }

            "hasClassName" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.className == s }
            }
            "hasClassNameEndsWith" -> {
                val s = (value as JsonPrimitive).content
                ;{ node -> node.className?.endsWith(s, ignoreCase = true) == true }
            }

            "isClickable" -> { node -> node.isClickable }
            "isEnabled" -> { node -> node.isEnabled }
            "isChecked" -> { node -> node.isChecked != 0 }
            "hasChildren" -> { node -> node.children.isNotEmpty() }
            "isLeaf" -> { node -> node.children.isEmpty() }

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

    data class ParsedStateBlock(
        val flow: Flow? = null,
        val modeHint: Mode? = null,
        val outcomes: Set<Flow>? = null,
    )

    fun parseStateBlock(stateObj: JsonObject?, ruleId: String): ParsedStateBlock {
        if (stateObj == null) return ParsedStateBlock()

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
        val outcomes = stateObj["outcomes"]?.jsonArray?.map { elem ->
            val wire = elem.jsonPrimitive.content
            Flow.fromWire(wire)
                ?: throw RuleCompileException("Rule '$ruleId': unknown outcome flow '$wire'")
        }?.toSet()

        return ParsedStateBlock(flow, modeHint, outcomes)
    }

    // ==========================================================================
    //  Helpers
    // ==========================================================================

    private fun readProperty(node: UiNode, prop: String): String? = when (prop) {
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
    private fun compileNavigation(navSpec: JsonElement): (UiNode) -> UiNode? {
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
            else -> throw RuleCompileException("Unknown navigation: '$nav'")
        }
    }

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

    /**
     * Compile a rule-supplied regex through the [RegexSafety] guard (length cap
     * + ReDoS rejection, #418). Kept here as the compiler's call site / public
     * entry point; the security logic lives in [RegexSafety] (audit #11).
     */
    internal fun compileRegex(pattern: String): Regex = RegexSafety.compileRegex(pattern)
}
