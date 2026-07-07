package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Compiles rule-declared `effects`/`transitionOverrides`/`onlyIf` gate blocks
 * (#239 extraction from `RuleCompiler`, pure move — no behavior change).
 * [RuleCompiler] keeps `compileEffectEntry`/`compileTransitionOverrides` as
 * thin `internal` delegates (external test call sites — `RuleCompilerTest`,
 * the `compiler.compileTransitionOverrides(...)` cases — are unaffected).
 *
 * Security-critical, unchanged by this move: [rejectedActuationWires] is the
 * #425 Pledge control that rejects rule-declared actuation verbs (`click`,
 * `tap`, `swipe`, …) at compile time — rules may only expose target
 * *bindings* for the app-owned `RuleAction` registry to consume. This reject
 * fires BEFORE [cloud.trotter.dashbuddy.domain.pipeline.EffectVerb.fromWire]
 * is even consulted, exactly as before the extraction.
 */
internal object EffectEntryCompiler {

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

    fun compileEffectEntry(obj: JsonObject): CompiledEffect {
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

    fun compileTransitionOverrides(
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

    private fun parseGateValue(element: kotlinx.serialization.json.JsonElement?): Any? {
        if (element == null) return null
        val prim = element.jsonPrimitive
        return prim.booleanOrNull
            ?: prim.longOrNull
            ?: prim.doubleOrNull
            ?: prim.content
    }
}
