package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.NotifTextField
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
import timber.log.Timber
import java.util.Locale

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
 * - [MAX_RULES_PER_FILE] — maximum rules in one platform file (enforced at load)
 * - [MAX_BRANCHES_PER_RULE] — maximum branches in one rule
 * - [MAX_EFFECTS_PER_RULE] — maximum effects a rule may declare
 */
object RuleCompiler {

    const val MAX_DEPTH = 20
    const val MAX_REGEX_LENGTH = 200
    private const val MAX_EACH_SIZE = 50

    /**
     * Maximum number of rules a single platform file may declare, summed across
     * its `screens` + `clicks` + `notifications` arrays (#419). Enforced at load
     * ([JsonRuleInterpreter.loadSingle]); an over-cap file is rejected as a typed
     * [RuleCompileException] and skipped WHOLE (the platform loads nothing behind
     * the #432 fail-closed gate — better than admitting a DoS bundle).
     *
     * Rationale: before this, only the 1 MB byte cap
     * ([JsonRuleInterpreter.MAX_FILE_BYTES]) bounded a bundle, and a 1 MB file can
     * pack tens of thousands of tiny rules. [Ruleset.matchFirst] is a LINEAR scan
     * over the merged ruleset on EVERY accessibility event; per-pattern time is
     * bounded (#680/#418) but the AGGREGATE scan is not — an oversized bundle is a
     * sustained-CPU DoS.
     *
     * Measured baseline (2026-07-05 generated assets): doordash = 103 rules
     * (72 screens + 14 clicks + 17 notifications), uber = 34. This cap is ~5× the
     * largest, so ordinary rules-repo growth never trips it; a future breach fires
     * the `DefaultRulesIntegrationTest` caps canary at test time BEFORE the runtime
     * reject.
     */
    const val MAX_RULES_PER_FILE = 500

    /**
     * Maximum number of branches a single rule may declare (#419). Every branch is
     * evaluated in order inside [Ruleset.matchFirst], so an unbounded branch list
     * is the same linear-scan-per-frame DoS as [MAX_RULES_PER_FILE] at rule scope.
     * Checked from the raw branch array in [compileRule] BEFORE the branches
     * compile, fail-closed as a [RuleCompileException].
     *
     * Measured baseline (2026-07-05): max 13 branches (the doordash multi-branch
     * `sensitive.known` rule). This cap is ~5× generous.
     */
    const val MAX_BRANCHES_PER_RULE = 64

    /**
     * Maximum number of effects a single rule may declare, summed across every
     * branch's `effects` + `transitionOverrides` (#419). Effects fire at
     * recognition time, so an unbounded list is an amplification vector. Checked in
     * [compileRule], fail-closed as a [RuleCompileException].
     *
     * Measured baseline (2026-07-05): max 2 effects in any rule (both platforms).
     * This cap is intentionally loose (effects are cheap) — an anti-DoS ceiling,
     * not a style bound.
     */
    const val MAX_EFFECTS_PER_RULE = 32

    /**
     * Maximum nesting depth of a parse expression (#590). The `each`/`extract`,
     * `join`, and `coalesce` compilers recurse into [compileParseExpression]
     * with NO structural bound of their own — a pathologically nested parse
     * block would overflow the JVM stack at COMPILE time. A [StackOverflowError]
     * is an [Error], not an [Exception], so it ESCAPES the `Exception`-only
     * catch in [JsonRuleInterpreter.loadSingle] (a fail-OPEN crash of the rule
     * load / classification path). This guard rejects over-deep parse
     * expressions as a typed [RuleCompileException] instead — fail closed.
     * Generous: real rules nest < 10.
     */
    const val MAX_PARSE_DEPTH = 64

    /**
     * Maximum nesting depth of raw rule JSON accepted before it reaches
     * kotlinx-serialization's recursive-descent parser (#590). The parser
     * overflows the stack on pathological nesting; the resulting
     * [StackOverflowError] escapes the `Exception`-only loader catch (fail
     * open). [parseBoundedJson] pre-scans depth and rejects past this bound as
     * a typed [RuleCompileException] before the recursive parser runs. Generous
     * (real rule files nest < 10); matches [MAX_PARSE_DEPTH].
     */
    const val MAX_JSON_DEPTH = 64

    /**
     * Parse rule JSON with a fail-CLOSED nesting bound (#590). A cheap linear
     * pre-scan counts `{`/`[` nesting (skipping string literals + escapes) and
     * throws [RuleCompileException] past [MAX_JSON_DEPTH] BEFORE
     * [Json.parseToJsonElement] — whose recursive descent would otherwise
     * [StackOverflowError] (an [Error] that escapes the loader's
     * `Exception`-only catch, a fail-open crash). The only place the rule
     * loader turns a raw string into JSON.
     */
    fun parseBoundedJson(jsonString: String): JsonElement {
        assertJsonDepthWithinBound(jsonString)
        return kotlinx.serialization.json.Json.parseToJsonElement(jsonString)
    }

    /** Linear depth pre-scan for [parseBoundedJson]; string-literal aware. */
    private fun assertJsonDepthWithinBound(json: String) {
        var depth = 0
        var maxDepth = 0
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> {
                        depth++
                        if (depth > maxDepth) maxDepth = depth
                        if (depth > MAX_JSON_DEPTH) {
                            throw RuleCompileException(
                                "Rule JSON nesting depth exceeds MAX_JSON_DEPTH=$MAX_JSON_DEPTH",
                            )
                        }
                    }
                    '}', ']' -> if (depth > 0) depth--
                }
            }
            i++
        }
    }

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
    fun <TInput> compileRules(
        array: JsonArray,
        context: RuleContext,
        platformId: String? = null,
    ): List<CompiledRule<TInput>> {
        // #293 item 4: per-rule fault isolation, OPT-IN (review F1). A rule
        // skips (WARN with id + reason, no rule-body text — P7) instead of
        // failing the whole file ONLY when BOTH:
        //   • the rejection is tagged [RuleCompileException.isolable] — a
        //     rule-authoring-level validation whose worst case is one
        //     recognition surface degrading to UNKNOWN (→ scrubbed — safe); AND
        //   • the rule is NOT part of the sensitive LAYER (id carries
        //     `.sensitive.`, `overrideable == false`, or an unreadable id) —
        //     never silently thin the sensitive block, whose coverage the #432
        //     check only guarantees as ≥1-survives, not all-survive.
        // Everything else — every UNTAGGED throw — rejects the WHOLE file (the
        // conservative pre-#293 status quo). The default direction is the
        // point: a future security/Pledge/DoS check (#419 caps, #598/#620/#624
        // capture-PII guards, #425 actuation, #590 depth bounds) that forgets
        // to consider isolation fails LOUD (over-reject), never as a silent
        // per-rule downgrade — Principle 6: do not trust call-site discipline.
        val rules = mutableListOf<CompiledRule<TInput>>()
        for (element in array) {
            val obj = element.jsonObject
            try {
                rules += compileRule<TInput>(obj, context, platformId)
            } catch (e: RuleCompileException) {
                if (!e.isolable || rawRuleIsSensitive(obj)) throw e
                Timber.tag("Rules").w(
                    "Skipping malformed %s rule '%s' (non-sensitive; frame → UNKNOWN → scrubbed): %s",
                    context.name.lowercase(Locale.ROOT), rawRuleId(obj), e.message,
                )
            }
        }
        // Enforce unique priorities within the same rule type
        val seen = mutableMapOf<Int, String>()
        for (rule in rules) {
            val existing = seen.put(rule.priority, rule.id)
            if (existing != null) {
                throw RuleCompileException(
                    "Duplicate priority ${rule.priority} in ${context.name.lowercase(Locale.ROOT)} rules: " +
                        "'$existing' and '${rule.id}'. Priorities must be unique per rule type.",
                )
            }
        }
        return rules
    }

    @Suppress("UNCHECKED_CAST")
    private fun <TInput> compileRule(
        obj: JsonObject,
        context: RuleContext,
        platformId: String? = null,
    ): CompiledRule<TInput> {
        // #293 item 3: typed id/priority — a missing/mistyped field is a
        // RuleCompileException naming the field, never a raw NPE/CCE from `!!`.
        val id = requireStringField(obj, "id", ruleId = null)
        val priority = requireIntField(obj, "priority", ruleId = id)

        // #293 item 5: unknown top-level rule keys (the `"overridable"` typo,
        // `"if_"`) are a typed reject naming them, not a silent default.
        validateKnownKeys(obj, knownRuleKeys(context), scope = "rule", ruleId = id)

        // #293 item 8: each rule id must carry the file's platform prefix. A
        // mis-prefixed id silently never matches today (Platform.fromRuleId reads
        // the leading segment) — make it loud. Platform-agnostic: the prefix is
        // whatever the file's platform_id declares, never a hardcoded platform.
        if (platformId != null) {
            val platformRoot = platformId.substringBefore('.')
            if (!id.startsWith("$platformRoot.")) {
                throw ruleError(
                    id,
                    "id must start with the file's platform prefix '$platformRoot.' " +
                        "(platform_id='$platformId')",
                )
            }
        }

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

        // #620 review F5: a `redact` on a CLICK-context rule silently vanishes —
        // `redact` compiles only for SCREEN, `notifRedact` only for NOTIFICATION —
        // the same silent-no-op class we reject for branch-level redact (VET V3).
        // Reject it (click envelopes carry app-vocabulary button labels, no PII).
        if (context == RuleContext.CLICK && "redact" in obj) {
            throw RuleCompileException(
                "Rule '$id': `redact` is not supported on CLICK rules — a click envelope carries " +
                    "app-vocabulary button labels, not customer PII. Remove the redact block.",
            )
        }

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

        // #419: bound the branch count from the RAW array, before compiling — an
        // over-cap branch list is a linear-scan-per-frame DoS at rule scope, and
        // rejecting here avoids compiling the pathological list at all.
        val rawBranchCount = (obj["branches"] as? JsonArray)?.size ?: 1
        if (rawBranchCount > MAX_BRANCHES_PER_RULE) {
            throw RuleCompileException(
                "Rule '$id' declares $rawBranchCount branches, exceeding " +
                    "MAX_BRANCHES_PER_RULE=$MAX_BRANCHES_PER_RULE",
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
                // #293 item 5: unknown branch keys are a typed reject naming them.
                validateKnownKeys(branchObj, knownBranchKeys(context), scope = "branch", ruleId = id)
                compileBranch(branchObj, context, ruleState.flow, ruleState.modeHint,
                    ruleOutcomes = ruleState.outcomes, ruleId = id,
                    ruleParseBlock = ruleParseObj, ruleParseAs = ruleParseAs, ruleBindObj = ruleBindObj)
            }
        } else {
            listOf(compileBranch(obj, context, ruleState.flow, ruleState.modeHint,
                ruleOutcomes = ruleState.outcomes, ruleId = id))
        }

        // #419: bound the total effect count across all branches (own effects +
        // transitionOverrides). Effects fire at recognition time; an unbounded
        // list is an amplification vector. Fail closed as a RuleCompileException.
        val effectCount = branches.sumOf { branch ->
            branch.effects.size + branch.transitionOverrides.values.sumOf { it.size }
        }
        if (effectCount > MAX_EFFECTS_PER_RULE) {
            throw RuleCompileException(
                "Rule '$id' declares $effectCount effects, exceeding " +
                    "MAX_EFFECTS_PER_RULE=$MAX_EFFECTS_PER_RULE",
            )
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
            val field = NotifTextField.fromWire(fieldName)
                ?: throw RuleCompileException(
                    "notification redact: unknown field '$fieldName' " +
                        "(expected one of ${NotifTextField.entries.map { it.wire }})",
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
                // #620 review F3: bound maskGroup at COMPILE time. An out-of-range
                // group would throw IndexOutOfBoundsException at capture (inside the
                // supervised upstream) → a crash/restart loop, once per matching
                // notification arrival. Group 0 is the whole match; 1..N the captures.
                val groupCount = regex.toPattern().matcher("").groupCount()
                if (group < 0 || group > groupCount) {
                    throw RuleCompileException(
                        "notification redact: field '$fieldName' maskGroup $group is out of range " +
                            "(pattern '$matchPattern' has $groupCount capturing group(s); valid 0..$groupCount)",
                    )
                }
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

    /**
     * Recursively scan raw rule JSON for a `"sha256"` transform value (#598).
     * Depth-bounded at [MAX_JSON_DEPTH] (#590): this walks the WHOLE raw rule
     * object, so a pathologically nested rule would overflow the stack here
     * before the parse-expression compiler's own [MAX_PARSE_DEPTH] guard runs.
     * Production JSON is already depth-bounded by [parseBoundedJson], so this
     * guard never trips for real rules; it makes [compileRules] fail-closed for
     * a JSON tree handed in directly.
     */
    private fun jsonUsesSha256(element: JsonElement, depth: Int = 0): Boolean {
        if (depth > MAX_JSON_DEPTH)
            throw RuleCompileException("Rule JSON nesting exceeds MAX_JSON_DEPTH=$MAX_JSON_DEPTH")
        return when (element) {
            is JsonPrimitive -> element.isString && element.content == "sha256"
            is JsonObject -> element.values.any { jsonUsesSha256(it, depth + 1) }
            is JsonArray -> element.any { jsonUsesSha256(it, depth + 1) }
        }
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
     * Compile a single parse expression for screen rules. [depth] bounds the
     * `each`/`join`/`coalesce` recursion at [MAX_PARSE_DEPTH] (#590) — a fail-
     * closed [RuleCompileException] in place of a stack-overflowing crash.
     */
    private fun compileParseExpression(
        spec: JsonElement,
        depth: Int = 0,
    ): (UiNode, Bindings) -> Any? {
        if (depth > MAX_PARSE_DEPTH)
            throw RuleCompileException("Parse expression nesting exceeds MAX_PARSE_DEPTH=$MAX_PARSE_DEPTH")

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
                val text = presenceSpec["allTextContains"]!!.jsonPrimitive.content.lowercase(Locale.ROOT)
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

    private fun compileValidateEntry(obj: JsonObject): (Map<String, Any?>) -> ValidateOutcome {
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
    //  #293 robustness helpers — typed casts, value-honoring flags, known keys
    // ==========================================================================

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

    /** A required string field on a rule object, typed (#293 item 3). */
    private fun requireStringField(obj: JsonObject, field: String, ruleId: String?): String {
        val el = obj[field]
            ?: throw ruleError(ruleId, "missing required '$field'")
        val prim = el as? JsonPrimitive
        if (prim == null || !prim.isString) {
            throw ruleError(ruleId, "'$field' must be a string, got: $el")
        }
        return prim.content
    }

    /** A required integer field on a rule object, typed (#293 item 3). */
    private fun requireIntField(obj: JsonObject, field: String, ruleId: String?): Int {
        val el = obj[field]
            ?: throw ruleError(ruleId, "missing required '$field'")
        return (el as? JsonPrimitive)?.intOrNull
            ?: throw ruleError(ruleId, "'$field' must be an integer, got: $el")
    }

    /**
     * A rule-authoring-level error (missing/mistyped id or priority, unknown
     * key, bad platform prefix) — tagged isolable: worst case is one surface
     * degrading to UNKNOWN. The isolation loop's sensitive belt still rejects
     * the whole file when the offending rule is sensitive-layer or its id is
     * unreadable (`rawRuleIsSensitive`).
     */
    private fun ruleError(ruleId: String?, msg: String): RuleCompileException =
        RuleCompileException(
            if (ruleId != null) "Rule '$ruleId': $msg" else "Rule: $msg",
            isolable = true,
        )

    // -- Known-key validation (#293 item 5) --------------------------------------
    //
    // Single owner for the rule/branch key vocabulary the compiler consumes. Built
    // from what compileRule/compileBranch actually read PLUS docs/rules.schema.json
    // (the compiler-ignored `comment`/`description` metadata keys are permitted).
    // An unknown key — the `"overridable"` typo, `"if_"` — is a typed reject naming
    // it, instead of silently defaulting. Production goldens are the proof this set
    // is right (a legitimate key that tripped it would fail DefaultRulesIntegrationTest).

    /** Metadata keys any rule/branch object may carry; ignored by the compiler. */
    private val META_KEYS = setOf("comment", "description")

    private fun knownRuleKeys(context: RuleContext): Set<String> = when (context) {
        RuleContext.SCREEN -> setOf(
            "id", "priority", "overrideable", "state", "bind", "redact", "reject",
            "require", "parse", "validate", "effects", "transitionOverrides",
            "branches", "intent",
        )
        RuleContext.CLICK -> setOf(
            "id", "priority", "overrideable", "state", "bind", "redact", "reject",
            "require", "parse", "validate", "effects", "transitionOverrides",
            "branches", "intent", "screenIs",
        )
        RuleContext.NOTIFICATION -> setOf(
            "id", "priority", "overrideable", "state", "redact", "reject",
            "require", "parse", "validate", "effects", "transitionOverrides",
            "branches", "intent",
        )
    } + META_KEYS

    private fun knownBranchKeys(context: RuleContext): Set<String> = when (context) {
        RuleContext.SCREEN -> setOf(
            "state", "bind", "reject", "require", "parse", "validate", "effects",
            "transitionOverrides", "intent",
        )
        RuleContext.CLICK -> setOf(
            "state", "bind", "reject", "require", "parse", "validate", "effects",
            "transitionOverrides", "intent", "screenIs",
        )
        RuleContext.NOTIFICATION -> setOf(
            "state", "reject", "require", "parse", "validate", "effects",
            "transitionOverrides", "intent",
        )
    } + META_KEYS

    private fun validateKnownKeys(
        obj: JsonObject,
        allowed: Set<String>,
        scope: String,
        ruleId: String?,
    ) {
        val unknown = obj.keys.filterNot { it in allowed }
        if (unknown.isNotEmpty()) {
            throw ruleError(ruleId, "unknown $scope key(s) $unknown (allowed: ${allowed.sorted()})")
        }
    }

    // -- Per-rule fault isolation, sensitive-exempt (#293 item 4) -----------------

    /**
     * True when a rule that FAILED to compile must still reject the WHOLE file
     * rather than be skipped (#293 item 4). Per-rule isolation must never silently
     * thin the sensitive block: the #432 coverage check only guarantees ≥1
     * sensitive rule per platform survives, not that ALL did. A skipped
     * non-sensitive rule degrades one recognition surface (its frames → UNKNOWN →
     * scrubbed — safe); a skipped sensitive rule degrades the Pledge. Read from RAW
     * JSON because the rule didn't compile: sensitive iff the readable `id` carries
     * the `.sensitive.` segment, OR `overrideable == false`, OR the `id` is
     * UNREADABLE (can't tell what it is → fail closed).
     */
    private fun rawRuleIsSensitive(obj: JsonObject): Boolean {
        val idPrim = obj["id"] as? JsonPrimitive
        val id = idPrim?.takeIf { it.isString }?.content
            ?: return true // unreadable id → fail closed
        if (".sensitive." in id) return true
        val overrideable = (obj["overrideable"] as? JsonPrimitive)?.booleanOrNull ?: true
        return !overrideable
    }

    /** Readable rule id for a WARN line, or a placeholder — never rule-body text (P7). */
    private fun rawRuleId(obj: JsonObject): String =
        (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "<unreadable-id>"

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
        if (depth > MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=$MAX_DEPTH")

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
        if (depth > MAX_DEPTH)
            throw RuleCompileException("Predicate nesting exceeds MAX_DEPTH=$MAX_DEPTH")

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
            else -> throw RuleCompileException(
                "Unknown navigation: '$nav'",
                isolable = true, // authoring typo — the rule isolates (#293 item 4)
            )
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
    internal fun compileRegex(pattern: String): BoundedRegex = RegexSafety.compileRegex(pattern)
}
