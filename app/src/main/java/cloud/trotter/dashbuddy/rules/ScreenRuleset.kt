package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * Immutable, pre-sorted list of compiled screen rules.
 *
 * Rules are sorted ascending by [CompiledScreenRule.priority] (lower number = evaluated first).
 * The first branch whose guards all pass (none fires) and whose condition returns true wins.
 */
class ScreenRuleset(rules: List<CompiledScreenRule>) {

    private val sorted: List<CompiledScreenRule> = rules.sortedBy { it.priority }

    val ruleCount: Int get() = sorted.size

    /**
     * Evaluate the sorted rules against [tree] and return the first matching [Screen],
     * or null if no rule matches (caller should fall back to [Screen.UNKNOWN]).
     */
    fun matchFirst(tree: UiNode): Screen? {
        for (rule in sorted) {
            for (branch in rule.branches) {
                // Any firing guard disqualifies this branch.
                if (branch.guards.any { it(tree) }) continue
                if (branch.condition(tree)) return branch.target
            }
        }
        return null
    }
}
