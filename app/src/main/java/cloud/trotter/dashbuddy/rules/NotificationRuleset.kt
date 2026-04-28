package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData

/**
 * Immutable, pre-sorted list of compiled notification rules.
 *
 * Rules are sorted ascending by priority (lower = evaluated first).
 * Each rule's [CompiledNotificationRule.classify] lambda handles both matching and extraction.
 */
class NotificationRuleset(rules: List<CompiledNotificationRule>) {

    private val sorted: List<CompiledNotificationRule> = rules.sortedBy { it.priority }

    val ruleCount: Int get() = sorted.size

    /**
     * Evaluate the sorted rules against [raw] and return the first matching [NotificationInfo],
     * or null if no rule matches (caller should fall back to [NotificationInfo.Unknown]).
     */
    fun classifyFirst(raw: RawNotificationData): NotificationInfo? {
        for (rule in sorted) {
            val result = rule.classify(raw)
            if (result != null) return result
        }
        return null
    }
}
