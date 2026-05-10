package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import timber.log.Timber

/**
 * Immutable, pre-sorted list of compiled screen rules.
 *
 * Rules are sorted ascending by [CompiledScreenRule.priority] (lower = evaluated first).
 * [matchFirst] runs the 5-phase pipeline (bind/reject/require/parse/validate) and returns
 * the first surviving branch as a [ScreenMatchResult].
 */
class ScreenRuleset(
    rules: List<CompiledScreenRule>,
    private val fieldsFactory: ParsedFieldsFactory = ParsedFieldsFactory,
) {

    private val sorted: List<CompiledScreenRule> = rules.sortedBy { it.priority }

    val ruleCount: Int get() = sorted.size

    /**
     * Evaluate the sorted rules against [tree] and return the first match,
     * or null if no rule matches (caller should fall back to UNKNOWN).
     *
     * When [platformWire] is non-null, only rules whose ID starts with that
     * platform prefix are evaluated (e.g. "doordash" only runs "doordash.*" rules).
     *
     * The 5-phase pipeline runs per branch:
     * 1. Resolve rule-level bindings (mandatory miss → skip rule)
     * 2. Resolve branch-level bindings (mandatory miss → skip branch)
     * 3. Reject checks (any true → skip branch)
     * 4. Require check (false → skip branch)
     * 5. Parse fields → ParsedFields via factory
     * 6. Validate assertions (Skip → next branch, DropParsed → match with None)
     */
    fun matchFirst(tree: UiNode, platformWire: String? = null): ScreenMatchResult? {
        val rules = if (platformWire != null) {
            sorted.filter { it.id.startsWith("$platformWire.") }
        } else {
            sorted
        }
        for (rule in rules) {
            // Phase 1: Resolve rule-level bindings
            val ruleBindings = mutableMapOf<String, UiNode?>()
            var ruleSkip = false
            for (binding in rule.bindings) {
                val found = binding.find(tree)
                if (found == null && !binding.optional) {
                    ruleSkip = true
                    break
                }
                ruleBindings[binding.name] = found
            }
            if (ruleSkip) continue

            for (branch in rule.branches) {
                // Phase 1b: Resolve branch-level bindings
                val allBindings = ruleBindings.toMutableMap()
                var branchSkip = false
                for (binding in branch.bindings) {
                    val found = binding.find(tree)
                    if (found == null && !binding.optional) {
                        branchSkip = true
                        break
                    }
                    allBindings[binding.name] = found
                }
                if (branchSkip) continue

                // Phase 2: Reject checks
                if (branch.rejectChecks.any { it(tree, allBindings) }) continue

                // Phase 3: Require check
                if (!branch.requireCheck(tree, allBindings)) continue

                // Phase 4: Parse
                val rawFields = try {
                    branch.parser(tree, allBindings)
                } catch (e: Exception) {
                    Timber.w(e, "Parse error in rule '${rule.id}' branch '${branch.target}'")
                    emptyMap()
                }

                // Phase 5: Validate
                var parsed = fieldsFactory.create(branch.parseShape, rawFields)
                for (validator in branch.validators) {
                    when (validator(rawFields)) {
                        is ValidateOutcome.Pass -> { /* continue */ }
                        is ValidateOutcome.Skip -> {
                            branchSkip = true
                            break
                        }
                        is ValidateOutcome.DropParsed -> {
                            parsed = ParsedFields.None
                            break
                        }
                    }
                }
                if (branchSkip) continue

                // Resolve compiled effects against current bindings
                val resolvedEffects = resolveEffects(branch.effects, allBindings, rule.id)

                // Resolve transition overrides (non-target effects only, no binding resolution needed)
                val resolvedOverrides = resolveTransitionOverrides(
                    branch.transitionOverrides, rule.id,
                )

                return ScreenMatchResult(
                    ruleId = rule.id,
                    target = branch.target,
                    flow = branch.flow,
                    modeHint = branch.modeHint,
                    parsed = parsed,
                    effects = resolvedEffects,
                    transitionOverrides = resolvedOverrides,
                )
            }
        }
        return null
    }

    /**
     * Resolve [CompiledEffect] bind names against current [bindings] to produce
     * [RequestedEffect]. For target-bound verbs (e.g. [EffectVerb.CLICK]),
     * effects whose target node is null are silently dropped. For non-target
     * verbs, [targetRef] is null and the effect always resolves.
     */
    private fun resolveEffects(
        effects: List<CompiledEffect>,
        bindings: Bindings,
        ruleId: String,
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
            args = effect.args,
            targetRef = targetRef,
            onlyIf = effect.onlyIf,
            dedupeKey = effect.dedupeKey,
            throttleMs = effect.throttleMs,
            ruleId = ruleId,
        )
    }

    /**
     * Resolve compiled transition overrides (wire-keyed) into
     * [TransitionTrigger]-keyed [RequestedEffect] lists.
     *
     * Transition override effects are non-target verbs (lifecycle / scheduling),
     * so no binding resolution is needed — just verb + args conversion.
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
                    targetRef = null, // lifecycle verbs never have targets
                    onlyIf = effect.onlyIf,
                    dedupeKey = effect.dedupeKey,
                    throttleMs = effect.throttleMs,
                    ruleId = ruleId,
                )
            }
        }
        return result
    }

    private fun buildNodeRef(node: UiNode): NodeRef {
        // Build a structural path fingerprint (depth capped at 8)
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
            pathFingerprint = pathParts.joinToString("/"),
        )
    }
}
