package cloud.trotter.dashbuddy.rules

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
     * Evaluate the sorted rules against [raw] and return the first match as a
     * [NotificationMatchResult], or null if no rule matches.
     *
     * When [platformWire] is non-null, only rules whose ID starts with that
     * platform prefix are evaluated.
     */
    fun classifyFirst(raw: RawNotificationData, platformWire: String? = null): NotificationMatchResult? {
        val rules = if (platformWire != null) {
            sorted.filter { it.id.startsWith("$platformWire.") }
        } else {
            sorted
        }
        for (rule in rules) {
            val result = rule.classify(raw)
            if (result != null) {
                return NotificationMatchResult(
                    ruleId = rule.id,
                    intent = result.intent,
                    fields = result.fields,
                    shape = rule.shape,
                    flow = rule.flow,
                    modeHint = rule.modeHint,
                )
            }
        }
        return null
    }
}
