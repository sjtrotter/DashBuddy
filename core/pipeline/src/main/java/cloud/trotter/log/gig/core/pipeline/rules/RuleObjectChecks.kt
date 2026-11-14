package cloud.trotter.log.gig.core.pipeline.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

// Rule-object checks used by [RuleCompiler.compileRules], split out of it (a pure move): typed
// id/priority reads, the known-key vocabulary (#293), and the per-rule isolation belt.

/** A required string field on a rule object, typed (#293 item 3). */
internal fun requireStringField(obj: JsonObject, field: String, ruleId: String?): String {
    val el = obj[field]
        ?: throw ruleError(ruleId, "missing required '$field'")
    val prim = el as? JsonPrimitive
    if (prim == null || !prim.isString) {
        throw ruleError(ruleId, "'$field' must be a string, got: $el")
    }
    return prim.content
}

/** A required integer field on a rule object, typed (#293 item 3). */
internal fun requireIntField(obj: JsonObject, field: String, ruleId: String?): Int {
    val el = obj[field]
        ?: throw ruleError(ruleId, "missing required '$field'")
    return (el as? JsonPrimitive)?.intOrNull
        ?: throw ruleError(ruleId, "'$field' must be an integer, got: $el")
}

/**
 * A rule's priority (#293 item 3), refused below zero for every source, bundled and remote (#1568). The
 * schema has always said `minimum: 0`; the compiler is the gate a remote rule set actually meets.
 */
internal fun requirePriority(obj: JsonObject, ruleId: String): Int {
    val priority = requireIntField(obj, "priority", ruleId)
    if (priority < 0) throw ruleError(ruleId, "'priority' must be 0 or more, got: $priority")
    return priority
}

/**
 * A rule-authoring-level error (missing/mistyped id or priority, unknown
 * key, bad platform prefix) — tagged isolable: worst case is one surface
 * degrading to UNKNOWN. The isolation loop's sensitive belt still rejects
 * the whole file when the offending rule is sensitive-layer or its id is
 * unreadable (`rawRuleIsSensitive`).
 */
internal fun ruleError(ruleId: String?, msg: String): RuleCompileException =
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

internal fun knownRuleKeys(context: RuleContext): Set<String> = when (context) {
    RuleContext.SCREEN -> setOf(
        "id", "priority", "overrideable", "state", "bind", "enables", "redact", "reject",
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

internal fun knownBranchKeys(context: RuleContext): Set<String> = when (context) {
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

internal fun validateKnownKeys(
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
internal fun rawRuleIsSensitive(obj: JsonObject): Boolean {
    val idPrim = obj["id"] as? JsonPrimitive
    val id = idPrim?.takeIf { it.isString }?.content
        ?: return true // unreadable id → fail closed
    if (".sensitive." in id) return true
    val overrideable = (obj["overrideable"] as? JsonPrimitive)?.booleanOrNull ?: true
    return !overrideable
}

/** Readable rule id for a WARN line, or a placeholder — never rule-body text (P7). */
internal fun rawRuleId(obj: JsonObject): String =
    (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "<unreadable-id>"
