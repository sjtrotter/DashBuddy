package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * Immutable, pre-sorted list of compiled click rules.
 *
 * Click predicates operate on a single [UiNode] (the tapped element) — no tree traversal.
 * Rules are sorted ascending by priority (lower = evaluated first).
 */
class ClickRuleset(rules: List<CompiledClickRule>) {

    private val sorted: List<CompiledClickRule> = rules.sortedBy { it.priority }

    val ruleCount: Int get() = sorted.size

    /**
     * Evaluate the sorted rules against [node] and return the first match as a
     * [ClickMatchResult], or null if no rule matches.
     */
    fun classifyFirst(node: UiNode): ClickMatchResult? {
        for (rule in sorted) {
            if (rule.condition(node)) {
                return ClickMatchResult(
                    ruleId = rule.id,
                    intent = rule.intentFactory(node),
                    flow = rule.flow,
                    modeHint = rule.modeHint,
                )
            }
        }
        return null
    }
}
