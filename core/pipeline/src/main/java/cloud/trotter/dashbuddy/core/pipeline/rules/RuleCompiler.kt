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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * - [MAX_RULES_PER_FILE] — maximum rules in one platform file (enforced at load)
 * - [MAX_BRANCHES_PER_RULE] — maximum branches in one rule
 * - [MAX_EFFECTS_PER_RULE] — maximum effects a rule may declare
 *
 * **This is the public facade (#239).** Rule/branch assembly (this file) is the
 * only logic that stays here; the predicate, parse-expression, and effect-entry
 * compilers that used to live in this one 1,776-line object are now extracted
 * `internal object`s — [PredicateCompiler], [ParseExpressionCompiler],
 * [EffectEntryCompiler] — plus package-level [CompilerHelpers]-style helpers in
 * `CompilerHelpers.kt`. Every external call site (`JsonRuleInterpreter`,
 * `TransformRegistry`, `Ruleset`, and every test under `rules/`) is unaffected —
 * `RuleCompiler.compileNodePred`/`compileTreePred`/`compileNotifPred`/
 * `compileEffectEntry`/`compileTransitionOverrides`/`compileRegex` are thin
 * delegates to the extracted objects, and every constant stays exactly where it
 * was. This is a pure move: no logic changed, no signature changed.
 */
object RuleCompiler {

    const val MAX_DEPTH = 20
    const val MAX_REGEX_LENGTH = 200

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
     * `join`, and `coalesce` compilers ([ParseExpressionCompiler]) recurse into
     * `compileParseExpression` with NO structural bound of their own — a
     * pathologically nested parse block would overflow the JVM stack at COMPILE
     * time. A [StackOverflowError] is an [Error], not an [Exception], so it
     * ESCAPES the `Exception`-only catch in [JsonRuleInterpreter.loadSingle] (a
     * fail-OPEN crash of the rule load / classification path). This guard rejects
     * over-deep parse expressions as a typed [RuleCompileException] instead — fail
     * closed. Generous: real rules nest < 10.
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

        // #733/#745 lint: POSITIONAL + BIDIRECTIONAL guard on `normalizeCustomerName`.
        // (a) a `customerNameHash` parse chain that hashes MUST place `normalizeCustomerName`
        //     immediately BEFORE `sha256` — else the same customer's name renders differently
        //     across surfaces (pickup "Brandy S" vs arrival "Brandy Smith") and hashes to
        //     DIFFERENT values, breaking the store-lineage join (the D6 NULL-store bug).
        // (b) `normalizeCustomerName` may NOT appear anywhere unless immediately FOLLOWED by
        //     `sha256` — a bare canonicalization would persist the customer's canonical name in
        //     the clear (a Pledge leak); it is a hash pre-image transform, nothing else.
        // Keyed on the enumerated CONTRACT field name (#8), not a platform. SCREEN-scoped.
        if (context == RuleContext.SCREEN) {
            validateCustomerNameNormalization(obj, id)
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
                compileBranch(branchObj, context, ruleState.flow, ruleState.modeHint, ruleId = id,
                    ruleParseBlock = ruleParseObj, ruleParseAs = ruleParseAs, ruleBindObj = ruleBindObj)
            }
        } else {
            listOf(compileBranch(obj, context, ruleState.flow, ruleState.modeHint, ruleId = id))
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
            // #733: opt a customer-NAME entry into canonical-key hashing so the mask hex
            // stays equal to the persisted `customerNameHash` (the parse normalizes the
            // same token). Rejected on any other value — fail loud, don't silently no-op.
            val normalize = obj["normalize"]?.jsonPrimitive?.content?.let { kind ->
                when (kind) {
                    "customerName" -> RedactNormalize.CUSTOMER_NAME
                    else -> throw RuleCompileException(
                        "redact entry: unknown normalize '$kind' (expected 'customerName')",
                    )
                }
            }
            CompiledRedactEntry(find, keepPrefix, normalize)
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

    /**
     * #733/#745 POSITIONAL + BIDIRECTIONAL lint on `normalizeCustomerName`. Reads transform ARRAYS in
     * ORDER (adjacency matters — a Set loses it). Two independent guards, both fail-closed:
     *  (a) [assertNameHashChainsNormalizedBeforeSha256] — every `sha256` inside a `customerNameHash`
     *      parse chain must be immediately preceded by `normalizeCustomerName`.
     *  (b) [assertNormalizeAlwaysBeforeSha256] — everywhere in the rule, `normalizeCustomerName` must
     *      be immediately followed by `sha256`; a bare/standalone use would persist the canonical name.
     * Depth-bounded like [jsonUsesSha256].
     */
    private fun validateCustomerNameNormalization(element: JsonElement, id: String) {
        assertNormalizeAlwaysBeforeSha256(element, id)
        walkNameHashFields(element, id)
    }

    /** True when [el] is the plain string transform token [name]. */
    private fun isTransformName(el: JsonElement?, name: String): Boolean =
        el is JsonPrimitive && el.isString && el.content == name

    private fun nameHashLintError(id: String): Nothing = throw RuleCompileException(
        "Screen rule '$id': a `customerNameHash` parse that hashes MUST place " +
            "`normalizeCustomerName` immediately before `sha256` (#733) — else the same customer's " +
            "name renders differently across surfaces and hashes distinctly, breaking the " +
            "store-lineage join. Insert \"normalizeCustomerName\" immediately before \"sha256\".",
    )

    private fun normalizeOrderLintError(id: String): Nothing = throw RuleCompileException(
        "Rule '$id': `normalizeCustomerName` must be immediately followed by `sha256` (#745) — a bare " +
            "canonicalization persists the customer's canonical name in the clear (a Pledge leak); it " +
            "is a hash pre-image transform only.",
    )

    /** (b) — wherever `normalizeCustomerName` appears it must be the array element immediately before
     *  `sha256`. A standalone use (object value, chain tail, wrong neighbour) is rejected. */
    private fun assertNormalizeAlwaysBeforeSha256(el: JsonElement, id: String, depth: Int = 0) {
        if (depth > MAX_JSON_DEPTH)
            throw RuleCompileException("Rule '$id' JSON nesting exceeds MAX_JSON_DEPTH=$MAX_JSON_DEPTH")
        when (el) {
            is JsonArray -> {
                el.forEachIndexed { i, child ->
                    if (isTransformName(child, "normalizeCustomerName") &&
                        !isTransformName(el.getOrNull(i + 1), "sha256")
                    ) {
                        normalizeOrderLintError(id)
                    }
                }
                el.forEach { assertNormalizeAlwaysBeforeSha256(it, id, depth + 1) }
            }
            is JsonObject -> el.values.forEach { v ->
                // A bare `"transform": "normalizeCustomerName"` (not in an array) can never be followed
                // by sha256 → reject; otherwise recurse.
                if (isTransformName(v, "normalizeCustomerName")) normalizeOrderLintError(id)
                assertNormalizeAlwaysBeforeSha256(v, id, depth + 1)
            }
            is JsonPrimitive -> {}
        }
    }

    /** (a) — walk to each `customerNameHash` field subtree and check its transform chains. */
    private fun walkNameHashFields(el: JsonElement, id: String, depth: Int = 0) {
        if (depth > MAX_JSON_DEPTH)
            throw RuleCompileException("Rule '$id' JSON nesting exceeds MAX_JSON_DEPTH=$MAX_JSON_DEPTH")
        when (el) {
            is JsonObject -> {
                el["customerNameHash"]?.let { assertNameHashChainsNormalizedBeforeSha256(it, id) }
                el.values.forEach { walkNameHashFields(it, id, depth + 1) }
            }
            is JsonArray -> el.forEach { walkNameHashFields(it, id, depth + 1) }
            is JsonPrimitive -> {}
        }
    }

    /** Within a `customerNameHash` field subtree (incl. coalesce branches), every `transform` chain's
     *  `sha256` must be immediately preceded by `normalizeCustomerName`; a bare `"sha256"` fails. */
    private fun assertNameHashChainsNormalizedBeforeSha256(field: JsonElement, id: String, depth: Int = 0) {
        if (depth > MAX_JSON_DEPTH)
            throw RuleCompileException("Rule '$id' JSON nesting exceeds MAX_JSON_DEPTH=$MAX_JSON_DEPTH")
        when (field) {
            is JsonObject -> {
                field["transform"]?.let { assertTransformShaPrecededByNormalize(it, id) }
                field.forEach { (k, v) -> if (k != "transform") assertNameHashChainsNormalizedBeforeSha256(v, id, depth + 1) }
            }
            is JsonArray -> field.forEach { assertNameHashChainsNormalizedBeforeSha256(it, id, depth + 1) }
            is JsonPrimitive -> {}
        }
    }

    /** For a `transform` spec: every `sha256` must be preceded (in its own array) by
     *  `normalizeCustomerName`. A bare `"sha256"` string (no chain) has no predecessor → fails. */
    private fun assertTransformShaPrecededByNormalize(t: JsonElement, id: String) {
        when (t) {
            is JsonPrimitive -> if (isTransformName(t, "sha256")) nameHashLintError(id)
            is JsonArray -> {
                t.forEachIndexed { i, e ->
                    if (isTransformName(e, "sha256") && !isTransformName(t.getOrNull(i - 1), "normalizeCustomerName")) {
                        nameHashLintError(id)
                    }
                }
                // A nested chain (rare — e.g. a `regex.then` array) — recurse into non-string elements.
                t.forEach { if (it !is JsonPrimitive) assertTransformShaPrecededByNormalize(it, id) }
            }
            is JsonObject -> t.values.forEach { assertTransformShaPrecededByNormalize(it, id) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <TInput> compileBranch(
        obj: JsonObject,
        context: RuleContext,
        ruleFlow: Flow? = null,
        ruleModeHint: Mode? = null,
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
                    val compiled = ParseExpressionCompiler.compileScreenParseBlock(parseBlock)
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
                    val compiled = ParseExpressionCompiler.compileNotificationParseBlock(parseBlock)
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
            ParseExpressionCompiler.compileValidateEntry(entry.jsonObject)
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
    //  State block parser (ADR-0005)
    // ==========================================================================

    data class ParsedStateBlock(
        val flow: Flow? = null,
        val modeHint: Mode? = null,
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

        return ParsedStateBlock(flow, modeHint)
    }

    // ==========================================================================
    //  Extracted-compiler delegates (#239) — thin forwards, zero call-site churn
    // ==========================================================================

    /** Delegates to [PredicateCompiler.compileTreePred]. */
    fun compileTreePred(json: JsonElement, depth: Int = 0): (UiNode) -> Boolean =
        PredicateCompiler.compileTreePred(json, depth)

    /** Delegates to [PredicateCompiler.compileNodePred]. */
    fun compileNodePred(json: JsonElement, depth: Int = 0): (UiNode) -> Boolean =
        PredicateCompiler.compileNodePred(json, depth)

    /** Delegates to [PredicateCompiler.compileNotifPred]. */
    fun compileNotifPred(json: JsonElement, depth: Int = 0): (RawNotificationData) -> Boolean =
        PredicateCompiler.compileNotifPred(json, depth)

    /** Delegates to [EffectEntryCompiler.compileEffectEntry]. Security-critical
     *  (#425 actuation-verb reject) — semantics unchanged by the extraction. */
    internal fun compileEffectEntry(obj: JsonObject): CompiledEffect =
        EffectEntryCompiler.compileEffectEntry(obj)

    /** Delegates to [EffectEntryCompiler.compileTransitionOverrides]. */
    internal fun compileTransitionOverrides(
        obj: JsonObject,
    ): Map<String, List<CompiledEffect>> = EffectEntryCompiler.compileTransitionOverrides(obj)

    /**
     * Compile a rule-supplied regex through the [RegexSafety] guard (length cap
     * + ReDoS rejection, #418). Kept here as the compiler's call site / public
     * entry point; the security logic lives in [RegexSafety] (audit #11).
     */
    internal fun compileRegex(pattern: String): BoundedRegex = RegexSafety.compileRegex(pattern)

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
}
