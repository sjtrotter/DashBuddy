package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import timber.log.Timber

/**
 * Immutable, pre-sorted list of compiled rules. Generic over input type.
 *
 * Rules are sorted ascending by [CompiledRule.priority] (lower = evaluated first).
 * [matchFirst] runs the pipeline (bind/reject/require/parse/validate) and returns
 * the first surviving branch as a [RuleMatchResult].
 *
 * For screen rules, `TInput` is [UiNode] (full tree, supports bindings).
 * For click rules, `TInput` is [UiNode] (single node, no bindings).
 * For notification rules, `TInput` is `RawNotificationData`.
 */
class Ruleset<TInput>(rules: List<CompiledRule<TInput>>) {

    private val sorted: List<CompiledRule<TInput>> = rules.sortedBy { it.priority }

    val ruleCount: Int get() = sorted.size

    /**
     * Evaluate the sorted rules against [input] and return the first match,
     * or null if no rule matches (caller should fall back to UNKNOWN).
     *
     * @param input The input to match against (UiNode tree, clicked node, or notification).
     * @param platformWire When non-null, only rules whose ID starts with this prefix are evaluated.
     * @param screenTarget When non-null, rules with a `screenIs` constraint only match
     *   if it equals this value. Used for click rules.
     */
    fun matchFirst(
        input: TInput,
        platformWire: String? = null,
        screenTarget: String? = null,
    ): RuleMatchResult? {
        val rules = if (platformWire != null) {
            sorted.filter { it.id.startsWith("$platformWire.") }
        } else {
            sorted
        }
        for (rule in rules) {
            // Phase 1: Resolve rule-level bindings (screen rules only)
            val ruleBindings = resolveBindings(input, rule.bindings) ?: continue

            for (branch in rule.branches) {
                // Screen constraint filter (click rules)
                if (branch.screenIs != null && branch.screenIs != screenTarget) continue

                // Phase 1b: Resolve branch-level bindings
                val branchBindings = resolveBindings(input, branch.bindings) ?: continue
                val allBindings = ruleBindings + branchBindings

                // Phase 2: Reject checks
                if (branch.rejectChecks.any { it(input) }) continue

                // Phase 3: Require check
                if (branch.predicate != null && !branch.predicate.invoke(input)) continue

                // Phase 4: Parse
                val rawFields = try {
                    branch.parser(input, allBindings)
                } catch (e: Exception) {
                    Timber.w(e, "Parse error in rule '${rule.id}'")
                    emptyMap()
                }

                // Phase 5: Validate
                var branchSkip = false
                var dropParsed = false
                for (validator in branch.validators) {
                    when (validator(rawFields)) {
                        is ValidateOutcome.Pass -> { /* continue */ }
                        is ValidateOutcome.Skip -> {
                            branchSkip = true
                            break
                        }
                        is ValidateOutcome.DropParsed -> {
                            dropParsed = true
                            break
                        }
                    }
                }
                if (branchSkip) continue

                val effectiveFields = if (dropParsed) emptyMap() else rawFields

                // Add intent to fields so ParsedFieldsFactory can access it
                val fieldsWithIntent = if (branch.intent != null) {
                    effectiveFields + ("intent" to branch.intent)
                } else {
                    effectiveFields
                }

                // Resolve compiled effects against current bindings + parsed fields
                val resolvedEffects = resolveEffects(
                    branch.effects, allBindings, rule.id, rawFields,
                )

                // Resolve transition overrides
                val resolvedOverrides = resolveTransitionOverrides(
                    branch.transitionOverrides, rule.id,
                )

                return RuleMatchResult(
                    ruleId = rule.id,
                    intent = branch.intent ?: deriveIntentFromId(rule.id),
                    shape = branch.shape,
                    flow = branch.flow,
                    modeHint = branch.modeHint,
                    outcomes = branch.outcomes,
                    fields = fieldsWithIntent,
                    effects = resolvedEffects,
                    transitionOverrides = resolvedOverrides,
                )
            }
        }
        return null
    }

    /**
     * Resolve bindings against [input]. Returns null if a mandatory binding
     * can't be resolved (indicating the rule/branch should be skipped).
     *
     * Only screen rules have bindings; for other rule types, this returns
     * an empty map immediately.
     */
    private fun resolveBindings(input: TInput, bindings: List<Binding>): Bindings? {
        if (bindings.isEmpty()) return emptyMap()
        // Bindings require a UiNode tree — if TInput isn't UiNode, skip
        val tree = input as? UiNode ?: return emptyMap()
        val result = mutableMapOf<String, UiNode?>()
        for (binding in bindings) {
            val found = binding.find(tree)
            if (found == null && !binding.optional) {
                return null // mandatory binding failed
            }
            result[binding.name] = found
        }
        return result
    }

    // =========================================================================
    // Effect resolution
    // =========================================================================

    /**
     * Resolve [CompiledEffect] bind names against current [bindings] to produce
     * [RequestedEffect]. For target-bound verbs (e.g. [EffectVerb.CLICK]),
     * effects whose target node is null are silently dropped.
     *
     * Template interpolation: `{fieldName}` references in args values and
     * dedupeKey are resolved against [parsedFields].
     */
    private fun resolveEffects(
        effects: List<CompiledEffect>,
        bindings: Bindings,
        ruleId: String,
        parsedFields: Map<String, Any?> = emptyMap(),
    ): List<RequestedEffect> = effects.mapNotNull { effect ->
        val targetRef = if (effect.targetBindName != null) {
            val node = bindings[effect.targetBindName]
            if (node == null) {
                Timber.v("Effect target '${effect.targetBindName}' not bound; skipping effect")
                return@mapNotNull null
            }
            buildNodeRef(node)
        } else {
            null
        }
        RequestedEffect(
            verb = effect.verb,
            args = resolveTemplateArgs(effect.args, parsedFields),
            targetRef = targetRef,
            onlyIf = effect.onlyIf,
            dedupeKey = effect.dedupeKey?.let { resolveTemplate(it, parsedFields) },
            throttleMs = effect.throttleMs,
            ruleId = ruleId,
        )
    }

    /**
     * Resolve compiled transition overrides (wire-keyed) into
     * [TransitionTrigger]-keyed [RequestedEffect] lists.
     */
    private fun resolveTransitionOverrides(
        overrides: Map<String, List<CompiledEffect>>,
        ruleId: String,
    ): Map<TransitionTrigger, List<RequestedEffect>> {
        if (overrides.isEmpty()) return emptyMap()
        val result = mutableMapOf<TransitionTrigger, List<RequestedEffect>>()
        for ((wireKey, compiledEffects) in overrides) {
            val trigger = TransitionTrigger.fromWire(wireKey)
            if (trigger == null) {
                Timber.w("Unknown transition trigger '%s' in rule '%s'; skipping", wireKey, ruleId)
                continue
            }
            result[trigger] = compiledEffects.map { effect ->
                RequestedEffect(
                    verb = effect.verb,
                    args = effect.args,
                    targetRef = null,
                    onlyIf = effect.onlyIf,
                    dedupeKey = effect.dedupeKey,
                    throttleMs = effect.throttleMs,
                    ruleId = ruleId,
                )
            }
        }
        return result
    }

    // =========================================================================
    // Template interpolation
    // =========================================================================

    companion object {
        const val MAX_TEMPLATE_VALUE_LENGTH = 256
        private val TEMPLATE_PATTERN = Regex("""\{(\w+)\}""")

        /**
         * Derive intent from a rule ID by stripping the platform prefix.
         * "doordash.screen.idle_map" → "idle_map"
         */
        private fun deriveIntentFromId(id: String): String {
            val parts = id.split(".", limit = 3)
            return if (parts.size >= 3) parts[2] else id
        }
    }

    private fun resolveTemplateArgs(
        args: Map<String, String>,
        fields: Map<String, Any?>,
    ): Map<String, String> {
        if (args.isEmpty() || fields.isEmpty()) return args
        if (args.values.none { it.contains('{') }) return args
        return args.mapValues { (_, value) -> resolveTemplate(value, fields) }
    }

    private fun resolveTemplate(template: String, fields: Map<String, Any?>): String {
        if (!template.contains('{')) return template
        return TEMPLATE_PATTERN.replace(template) { match ->
            val fieldName = match.groupValues[1]
            val value = fields[fieldName]
            if (value != null) sanitizeTemplateValue(value.toString()) else match.value
        }
    }

    private fun sanitizeTemplateValue(value: String): String {
        return value
            .replace(Regex("[\\p{Cc}\\p{Cn}]"), "")
            .take(MAX_TEMPLATE_VALUE_LENGTH)
    }

    private fun buildNodeRef(node: UiNode): NodeRef {
        val pathParts = mutableListOf<String>()
        var current: UiNode? = node
        var depth = 0
        while (current != null && depth < 8) {
            val cls = current.className?.substringAfterLast('.') ?: "?"
            val idx = current.parent?.children?.indexOf(current) ?: 0
            pathParts.add(0, "$cls[$idx]")
            current = current.parent
            depth++
        }
        return NodeRef(
            viewIdSuffix = node.viewIdResourceName,
            text = node.text?.take(50),
            classNameHint = node.className,
            boundsInScreen = node.boundsInScreen,
            pathFingerprint = pathParts.joinToString("/"),
        )
    }
}
