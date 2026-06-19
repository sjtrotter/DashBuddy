package cloud.trotter.dashbuddy.domain.state

import java.util.Locale

/**
 * Identifies a gig platform. Derived from the platform prefix of a rule's
 * `id` field (e.g., `"doordash.screen.offer"` → [DoorDash]).
 *
 * New platforms are added here and in the corresponding ruleset. The
 * state machine creates a [PlatformRegion] per platform automatically
 * on first observation.
 *
 * Display metadata lives here as the SSOT (audit #9): [displayName] (settings
 * label), [shortName] (bubble HUD badge), and [sessionVerb] (the dispatcher
 * chat gerund — "Dashing"/"Ubering"; `null` falls back to a generic verb at
 * the UI edge). No UI/Android types — this is pure copy + a phrase, derived
 * everywhere it's shown.
 */
enum class Platform(
    val wire: String,
    val packageName: String?,
    val displayName: String,
    val shortName: String,
    val sessionVerb: String?,
) {
    DoorDash("doordash", "com.doordash.driverapp", "DoorDash", "DD", "Dashing"),
    Uber("uber", "com.ubercab.driver", "Uber Driver", "Uber", "Ubering"),
    Instacart("instacart", "com.instacart.shopper", "Instacart Shopper", "IC", null),
    WalmartSpark("walmart_spark", "com.walmart.spark", "Walmart Spark", "Spark", null),
    Unknown("_unknown", null, "Unknown", "", null),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        private val byName = entries.associateBy { it.name.lowercase(Locale.ROOT) }

        /** Resolve the platform prefix from a rule id, or [Unknown]. */
        fun fromRuleId(ruleId: String?): Platform {
            val prefix = ruleId?.substringBefore('.') ?: return Unknown
            return byWire[prefix] ?: Unknown
        }

        fun fromWire(wire: String): Platform? = byWire[wire]

        /**
         * Resolve a platform from a serialized identifier — either an enum
         * constant name (e.g. `"DoorDash"`, the form [EffectMap] carries via
         * `platform.name`) or a wire prefix (`"doordash"`). Case-insensitive,
         * [Locale.ROOT]. Returns `null` when nothing matches so callers can
         * choose their own fallback.
         */
        fun fromName(name: String?): Platform? {
            val key = name?.lowercase(Locale.ROOT) ?: return null
            return byName[key] ?: byWire[key]
        }

        private val byPackage = entries
            .filter { it.packageName != null }
            .associateBy { it.packageName }

        /** Resolve the platform from a source package name, or [Unknown]. */
        fun fromPackage(packageName: String?): Platform =
            byPackage[packageName] ?: Unknown

        /** All known package names for OS-level event subscription. */
        val watchedPackages: Set<String> =
            entries.mapNotNull { it.packageName }.toSet()
    }
}
