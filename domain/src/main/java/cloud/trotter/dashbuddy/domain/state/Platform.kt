package cloud.trotter.dashbuddy.domain.state

/**
 * Identifies a gig platform. Derived from the platform prefix of a rule's
 * `id` field (e.g., `"doordash.screen.offer"` → [DoorDash]).
 *
 * New platforms are added here and in the corresponding ruleset. The
 * state machine creates a [PlatformRegion] per platform automatically
 * on first observation.
 */
enum class Platform(val wire: String) {
    DoorDash("doordash"),
    Uber("uber"),
    Instacart("instacart"),
    WalmartSpark("walmart_spark"),
    Unknown("_unknown"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Resolve the platform prefix from a rule id, or [Unknown]. */
        fun fromRuleId(ruleId: String?): Platform {
            val prefix = ruleId?.substringBefore('.') ?: return Unknown
            return byWire[prefix] ?: Unknown
        }

        fun fromWire(wire: String): Platform? = byWire[wire]
    }
}
