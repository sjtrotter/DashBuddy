package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.RequestedAction
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
     * The 5-phase pipeline runs per branch:
     * 1. Resolve rule-level bindings (mandatory miss → skip rule)
     * 2. Resolve branch-level bindings (mandatory miss → skip branch)
     * 3. Reject checks (any true → skip branch)
     * 4. Require check (false → skip branch)
     * 5. Parse fields → ParsedFields via factory
     * 6. Validate assertions (Skip → next branch, DropParsed → match with None)
     */
    fun matchFirst(tree: UiNode): ScreenMatchResult? {
        for (rule in sorted) {
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

                // Resolve compiled actions against current bindings
                val resolvedActions = resolveActions(branch.actions, allBindings, rule.id)

                return ScreenMatchResult(
                    ruleId = rule.id,
                    target = branch.target,
                    flow = branch.flow,
                    modeHint = branch.modeHint,
                    parsed = parsed,
                    actions = resolvedActions,
                )
            }
        }
        return null
    }

    /**
     * Resolve [CompiledAction] bind names against current [bindings] to produce
     * [RequestedAction] with concrete [NodeRef]. Actions whose target node is
     * null are silently dropped (rule still matches — ADR-0006 §4).
     */
    private fun resolveActions(
        actions: List<CompiledAction>,
        bindings: Bindings,
        ruleId: String,
    ): List<RequestedAction> = actions.mapNotNull { action ->
        val node = bindings[action.targetBindName]
        if (node == null) {
            Timber.v("Action target '${action.targetBindName}' not bound; skipping action")
            return@mapNotNull null
        }
        RequestedAction(
            verb = action.verb,
            targetRef = buildNodeRef(node),
            onlyIf = action.onlyIf,
            dedupeKey = action.dedupeKey,
            throttleMs = action.throttleMs,
            ruleId = ruleId,
        )
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
