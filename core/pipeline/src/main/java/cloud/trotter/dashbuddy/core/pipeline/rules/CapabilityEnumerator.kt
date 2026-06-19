package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Derives the consent keys for a compiled ruleset (#422/#425). Extracted from
 * [RuleCompiler] (audit #11) so the security-load-bearing key derivation has a
 * findable, independently-testable home, depending only on [CompiledRule] /
 * [Binding] and the [sha256OrNull] SSOT — not on the rest of the compiler.
 *
 * The unit of user consent is one [RuleCapability] per (rule, action) whose
 * well-known target bind name the rule binds. The key content-pins consent to
 * the binding DEFINITION, so a remote update that repoints a binding forces
 * re-consent.
 */
internal object CapabilityEnumerator {

    /**
     * Enumerate the app-owned actions a compiled ruleset's bindings enable —
     * one [RuleCapability] per (rule, action) whose well-known target bind name
     * ([RuleAction.targetBindName]) the rule binds. Deduped by
     * [RuleCapability.key] (the same binding compiled into several branches is
     * one capability). [source] is recorded for provenance only — consent is
     * uniform regardless of where the rule came from.
     *
     * The key hashes `(ruleId, action, the CANONICAL binding definition)` —
     * pinned to the predicate that selects the node, so repointing the binding
     * (even with the same bind name) forces re-consent. The execution gate
     * (#417) looks grants up from this enumeration at fire time; nothing is
     * threaded through the effect pipeline.
     */
    fun enumerate(
        rules: List<CompiledRule<*>>,
        source: String,
    ): List<RuleCapability> {
        val byKey = LinkedHashMap<String, RuleCapability>()
        fun consider(ruleId: String, bindings: List<Binding>) {
            for (binding in bindings) {
                val action = RuleAction.byTargetBindName[binding.name] ?: continue
                // Structurally-unambiguous key input (#422): a canonical JSON
                // object, NOT delimiter-joined fields. Rule ids and bind names
                // are arbitrary JSON strings (no charset constraint), so JSON
                // string escaping is what makes the field boundaries exact —
                // distinct tuples can never collide on the same input.
                val keyInput = canonicalJson(
                    buildJsonObject {
                        put("rule", ruleId)
                        put("action", action.wire)
                        put("bind", binding.name)
                        put("def", binding.defJson ?: JsonNull)
                    },
                )
                val key = sha256OrNull(keyInput) ?: continue // fail closed: unkeyable → not consentable
                byKey.getOrPut(key) {
                    RuleCapability(
                        ruleId = ruleId,
                        action = action,
                        targetBindName = binding.name,
                        key = key,
                        source = source,
                    )
                }
            }
        }
        for (rule in rules) {
            consider(rule.id, rule.bindings)
            for (branch in rule.branches) consider(rule.id, branch.bindings)
        }
        return byKey.values.toList()
    }

    /** Stable serialization (recursively sorted object keys) so reordering a
     *  binding's keys doesn't change its consent key (#422). */
    private fun canonicalJson(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries
            .sortedBy { it.key }
            .joinToString(",", "{", "}") { "${it.key}:${canonicalJson(it.value)}" }
        is JsonArray -> element.joinToString(",", "[", "]") { canonicalJson(it) }
        is JsonPrimitive -> element.toString()
    }
}
