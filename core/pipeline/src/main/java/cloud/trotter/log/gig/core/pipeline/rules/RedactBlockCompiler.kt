package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.notification.NotifTextField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The capture-redaction half of rule compilation, split out of [RuleCompiler] (a pure move): the
 * screen `redact` array (#598), the notification `redact` object (#620), and the raw-JSON lints that
 * tie a rule's hashing to its redaction (#598 sha256 scan, #733/#745 `normalizeCustomerName` order).
 */
internal object RedactBlockCompiler {

    /**
     * Compile a notification rule's `redact` OBJECT (#620), keyed by field name
     * (title/text/bigText/tickerText/subText). Each value is either a whole-field
     * spec (optional `keepPrefix`) or a regex-capture spec (`match` + `maskGroup`,
     * default group 1). Regexes go through the bounded [compileRegex]/[RegexSafety]
     * seam — notification rules are untrusted input on the #192 CDN path too.
     */
    fun compileNotifRedactBlock(obj: JsonObject): CompiledNotifRedact {
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
            // #987: `plainMask` is the flat-string twin of the screen redact's #795 flag — it
            // suppresses the <4hex> distinctness suffix for a field whose masked remainder has a
            // SMALL plaintext space (the earnings-deposit clause, where the only variable is the
            // dasher's own banking amount). It is meaningless on the regex-capture form (the
            // group mask has its own fail-closed path and no keepPrefix), so declaring both is a
            // loud reject rather than a silently ignored flag — the #795 posture.
            val plainMask = compilePlainMask(specObj["plainMask"], "notification redact: field '$fieldName'")
            if (plainMask && matchPattern != null) {
                throw RuleCompileException(
                    "notification redact: field '$fieldName' declares BOTH 'plainMask' and " +
                        "'match' — 'plainMask' shapes the whole-field mask only. Drop one.",
                )
            }
            val masker: NotifFieldMask = if (matchPattern != null) {
                val regex = compileRegex(matchPattern)
                val group = specObj["maskGroup"]?.jsonPrimitive?.intOrNull ?: 1
                // #620 review F3: bound maskGroup at COMPILE time. An out-of-range
                // group would throw IndexOutOfBoundsException at capture (inside the
                // supervised upstream) → a crash/restart loop, once per matching
                // notification arrival. Group 0 is the whole match; 1..N the captures.
                val groupCount = regex.groupCount()
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
                NotifFieldMask.Whole(keepPrefix, plainMask)
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
    fun compileRedactBlock(array: JsonArray): CompiledRedact {
        val entries = array.map { element ->
            val obj = element as? JsonObject
                ?: throw RuleCompileException("redact entry must be an object with a 'find' predicate")
            val findSpec = obj["find"]
                ?: throw RuleCompileException("redact entry has no 'find' predicate")
            val find = PredicateCompiler.compileNodePred(findSpec)
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
            // #795: `plainMask` suppresses the <4hex> distinctness suffix so a small-space
            // secret (a PIN keypad node) redacts to plain `[redacted]`, not a reversible hash.
            // It is mutually exclusive with `normalize` (both shape the hash; a plain mask has
            // no hash to shape) — fail loud rather than silently ignore one.
            val plainMask = compilePlainMask(obj["plainMask"], "redact entry")
            if (plainMask && normalize != null) {
                throw RuleCompileException(
                    "redact entry: `plainMask` and `normalize` are mutually exclusive — a plain " +
                        "mask emits `[redacted]` with no distinctness hash for `normalize` to shape.",
                )
            }
            CompiledRedactEntry(find, keepPrefix, normalize, plainMask)
        }
        return CompiledRedact(entries)
    }

    /**
     * Recursively scan raw rule JSON for a `"sha256"` transform value (#598).
     * Depth-bounded at [RuleCompiler.MAX_JSON_DEPTH] (#590): this walks the WHOLE raw rule
     * object, so a pathologically nested rule would overflow the stack here
     * before the parse-expression compiler's own [MAX_PARSE_DEPTH] guard runs.
     * Production JSON is already depth-bounded by [parseBoundedJson], so this
     * guard never trips for real rules; it makes [compileRules] fail-closed for
     * a JSON tree handed in directly.
     */
    fun jsonUsesSha256(element: JsonElement, depth: Int = 0): Boolean {
        if (depth > RuleCompiler.MAX_JSON_DEPTH)
            throw RuleCompileException("Rule JSON nesting exceeds RuleCompiler.MAX_JSON_DEPTH=$RuleCompiler.MAX_JSON_DEPTH")
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
    fun validateCustomerNameNormalization(element: JsonElement, id: String) {
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
        if (depth > RuleCompiler.MAX_JSON_DEPTH)
            throw RuleCompileException("Rule '$id' JSON nesting exceeds RuleCompiler.MAX_JSON_DEPTH=$RuleCompiler.MAX_JSON_DEPTH")
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
        if (depth > RuleCompiler.MAX_JSON_DEPTH)
            throw RuleCompileException("Rule '$id' JSON nesting exceeds RuleCompiler.MAX_JSON_DEPTH=$RuleCompiler.MAX_JSON_DEPTH")
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
        if (depth > RuleCompiler.MAX_JSON_DEPTH)
            throw RuleCompileException("Rule '$id' JSON nesting exceeds RuleCompiler.MAX_JSON_DEPTH=$RuleCompiler.MAX_JSON_DEPTH")
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

    /**
     * `plainMask` is a privacy switch: absent means false, but a PRESENT non-boolean (`1`, `"true"`,
     * `null`) must not quietly read as false — that would re-enable the hashed suffix over a small
     * plaintext space, the exact inversion oracle the flag exists to remove (#987 review). The
     * runtime loader performs no JSON-Schema validation, so the compiler is the only gate.
     */
    private fun compilePlainMask(element: JsonElement?, where: String): Boolean {
        if (element == null) return false
        val prim = element as? JsonPrimitive
        val value = prim?.takeIf { !it.isString }?.booleanOrNull
        return value ?: throw RuleCompileException(
            "$where: 'plainMask' must be a JSON boolean (true/false); got '$element'",
        )
    }
}
