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
 * the sorted set of binding DEFINITIONS across every branch (#1167), so a
 * remote update that repoints any binding forces re-consent.
 */
internal object CapabilityEnumerator {

    /**
     * Enumerate the app-owned actions a compiled ruleset's bindings enable —
     * one [RuleCapability] per (rule, action) whose well-known target bind name
     * ([RuleAction.targetBindName]) the rule binds (#1167). Definitions shared
     * by several branches count as one layout. [source] is recorded for
     * provenance only — consent is uniform regardless of where the rule came from.
     *
     * The key hashes `(ruleId, action, the SORTED SET of canonical binding definitions)` —
     * repointing any branch or adding a layout forces re-consent; reordering
     * branches does not. The execution gate
     * (#417) looks grants up from this enumeration at fire time; nothing is
     * threaded through the effect pipeline.
     */
    fun enumerate(
        rules: List<CompiledRule<*>>,
        source: String,
    ): List<RuleCapability> {
        val capabilities = mutableListOf<RuleCapability>()
        for (rule in rules) {
            val definitionsByAction = (rule.bindings + rule.branches.flatMap { it.bindings })
                .mapNotNull { binding ->
                    RuleAction.byTargetBindName[binding.name]?.let { action ->
                        action to canonicalJson(binding.defJson ?: JsonNull)
                    }
                }
                .groupBy({ it.first }, { it.second })
            for (action in RuleAction.entries) {
                val defs = definitionsByAction[action]?.distinct()?.sorted() ?: continue
                // Structurally-unambiguous key input (#422): a canonical JSON
                // object, NOT delimiter-joined fields. Rule ids and bind names
                // are arbitrary JSON strings (no charset constraint), so JSON
                // string escaping is what makes the field boundaries exact —
                // distinct tuples can never collide on the same input.
                val keyInput = canonicalJson(
                    buildJsonObject {
                        put("rule", rule.id)
                        put("action", action.wire)
                        put("bind", action.targetBindName)
                        put("defs", JsonArray(defs.map { JsonPrimitive(it) }))
                    },
                )
                val key = sha256OrNull(keyInput) ?: continue // fail closed: unkeyable → not consentable
                capabilities += RuleCapability(
                    ruleId = rule.id,
                    action = action,
                    targetBindName = action.targetBindName,
                    key = key,
                    source = source,
                    layoutCount = defs.size,
                )
            }
        }
        return capabilities
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
