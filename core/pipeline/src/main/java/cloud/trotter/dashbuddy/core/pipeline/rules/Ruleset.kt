package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
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

    /**
     * Match-time evaluation order (#419): the NON-overrideable rules
     * ([CompiledRule.overrideable] == false), priority-ordered, THEN the
     * overrideable rules, priority-ordered. Partitioned ONCE at construction,
     * never per-frame.
     *
     * This makes `overrideable: false` structurally real. Previously the
     * sensitive-screen block relied only on priority-0-first + unique-priority +
     * first-match, and the parsed [CompiledRule.overrideable] flag was inert — a
     * replacement bundle (or a future multi-source merge, #192) that gamed
     * priorities, giving a non-sensitive rule a priority number below a sensitive
     * rule's, could pre-empt the sensitive classification. Evaluating the
     * non-overrideable partition first means no priority value in any later/other
     * source can pre-empt a non-overrideable (sensitive) classification.
     *
     * Byte-inert for today's assets: the only shipped `overrideable: false` rules
     * are the priority-0 `sensitive.known` blocks (and the priority-0
     * `crimson_balance` notification), which a pure-priority sort already put
     * first — so this partition leaves the evaluation order identical. The broad
     * `sensitive.catchall` fallback is `overrideable: true` (priority 999): a
     * deliberate last-resort that specific recognition is meant to override, so it
     * stays in the overrideable partition and keeps its last position.
     */
    private val evalOrder: List<CompiledRule<TInput>> =
        sorted.filterNot { it.overrideable } + sorted.filter { it.overrideable }

    /** Rule lookup by id (#598) — the capture-redaction seam fetches a
     *  recognized rule's compiled `redact` block without exposing the list. */
    private val byId: Map<String, CompiledRule<TInput>> = sorted.associateBy { it.id }

    val ruleCount: Int get() = sorted.size

    /** The compiled rule with this id, or null if none. */
    fun ruleById(id: String): CompiledRule<TInput>? = byId[id]

    /**
     * Evaluate the rules against [input] and return the first match, or null if
     * no rule matches (caller should fall back to UNKNOWN).
     *
     * Rules are walked in [evalOrder]: the non-overrideable partition first
     * (priority-ordered), then the overrideable partition (priority-ordered) — so
     * a non-overrideable (sensitive) classification can never be pre-empted by a
     * lower-priority-number rule from another/later source (#419).
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
            evalOrder.filter { it.id.startsWith("$platformWire.") }
        } else {
            evalOrder
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

                // Resolve compiled effects against parsed fields
                val resolvedEffects = resolveEffects(
                    branch.effects, rule.id, rawFields,
                )

                // Resolve transition overrides
                val resolvedOverrides = resolveTransitionOverrides(
                    branch.transitionOverrides, rule.id,
                )

                // Expose resolved bindings as named targets (#425) —
                // recognition-layer data for the app-owned action registry.
                val targets = buildMap {
                    for ((name, node) in allBindings) {
                        if (node != null) put(name, buildNodeRef(node))
                    }
                }

                return RuleMatchResult(
                    ruleId = rule.id,
                    intent = branch.intent ?: RuleCompiler.deriveTargetFromId(rule.id),
                    shape = branch.shape,
                    flow = branch.flow,
                    modeHint = branch.modeHint,
                    outcomes = branch.outcomes,
                    fields = fieldsWithIntent,
                    effects = resolvedEffects,
                    targets = targets,
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
     * Resolve [CompiledEffect]s to [RequestedEffect]s. All rule effects are
     * observational/app-internal (#425) — no targets, no consent keys.
     *
     * Template interpolation: `{fieldName}` references in args values and
     * dedupeKey are resolved against [parsedFields].
     */
    private fun resolveEffects(
        effects: List<CompiledEffect>,
        ruleId: String,
        parsedFields: Map<String, Any?> = emptyMap(),
    ): List<RequestedEffect> = effects.map { effect ->
        RequestedEffect(
            verb = effect.verb,
            args = resolveTemplateArgs(effect.args, parsedFields),
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
