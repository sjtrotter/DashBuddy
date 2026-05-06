package cloud.trotter.dashbuddy.domain.state

/**
 * Identifies a gig platform. Derived from the platform prefix of a rule's
 * `id` field (e.g., `"doordash.screen.offer"` → [DoorDash]).
 *
 * New platforms are added here and in the corresponding ruleset. The
 * state machine creates a [PlatformRegion] per platform automatically
 * on first observation.
 */
enum class Platform(val wire: String, val packageName: String?) {
    DoorDash("doordash", "com.doordash.driverapp"),
    Uber("uber", "com.ubercab.driver"),
    Instacart("instacart", "com.instacart.shopper"),
    WalmartSpark("walmart_spark", "com.walmart.spark"),
    Unknown("_unknown", null),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        /** Resolve the platform prefix from a rule id, or [Unknown]. */
        fun fromRuleId(ruleId: String?): Platform {
            val prefix = ruleId?.substringBefore('.') ?: return Unknown
            return byWire[prefix] ?: Unknown
        }

        fun fromWire(wire: String): Platform? = byWire[wire]

        private val byPackage = entries
            .filter { it.packageName != null }
            .associateBy { it.packageName }

        /** Resolve the platform from a source package name, or [Unknown]. */
        fun fromPackage(packageName: String?): Platform =
            byPackage[packageName] ?: Unknown

        /** All known package names for OS-level event subscription. */
        fun watchedPackages(): Set<String> =
            entries.mapNotNull { it.packageName }.toSet()
    }
}
