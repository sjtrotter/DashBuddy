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
     *
     * When [platformWire] is non-null, only rules whose ID starts with that
     * platform prefix are evaluated. When [screenTarget] is non-null, rules
     * with a `screenIs` constraint only match if it equals the active screen.
     */
    fun classifyFirst(node: UiNode, platformWire: String? = null, screenTarget: String? = null): ClickMatchResult? {
        val rules = sorted
            .filter { platformWire == null || it.id.startsWith("$platformWire.") }
            .filter { it.screenConstraint == null || it.screenConstraint == screenTarget }
        for (rule in rules) {
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
