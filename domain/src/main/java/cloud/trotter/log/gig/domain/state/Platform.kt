package cloud.trotter.log.gig.domain.state

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
 * label, and the dispatcher chat's "<displayName> shift") and [shortName] (bubble
 * HUD badge), and [appName] (plain app name in offer titles). No UI/Android types — this is pure copy, derived everywhere it's shown.
 *
 * [offerOverlay] (#1152) declares that the platform draws its offer as a floating overlay above
 * other apps (a `SYSTEM_ALERT_WINDOW`, reported to accessibility services as `TYPE_SYSTEM`) — the
 * sensor layer then admits that platform's large system-layer windows as snapshot candidates (by
 * size + package). Adding an overlay platform is flipping this one flag (principle 8).
 *
 * [bound] (#170) declares that gig.log ships a ruleset for the platform. Which apps a build reads is
 * [readable]: the bound platforms, or, in the one build that reads the unbound ones too (the debug build, so a
 * tester's unrecognized screens from those apps reach the census; the build table's `readsUnboundPlatforms`),
 * every platform with a package. The service subscription, the enabled-platform preference, the platform settings
 * list and the permission card's app names all derive from [readable]. In every other build a platform with no
 * ruleset stays a registry entry (other code keys on it) but is never read, whatever a saved preference says.
 * `AccessibilityPackageParityTest` pins, per build, the readable set to the service config's `packageNames` and the
 * manifest's `<queries>`, and the bound set to the generated rules assets.
 *
 * [wire] values are assumed **non-empty and dot-free** — they are used as the
 * platform namespace prefix of rule ids (`<wire>.<section>.<name>`), so a wire
 * containing a `.` or an empty wire would break prefix-based resolution
 * ([fromRuleId], `Ruleset.evalOrderByPlatform`).
 */
enum class Platform(
    val wire: String,
    val packageName: String?,
    val displayName: String,
    val shortName: String,
    val appName: String,
    val offerOverlay: Boolean = false,
    val bound: Boolean = false,
) {
    DoorDash("doordash", "com.doordash.driverapp", "DoorDash", "DD", "DoorDash", bound = true),
    Uber("uber", "com.ubercab.driver", "Uber Driver", "Uber", "Uber", offerOverlay = true, bound = true),
    Instacart("instacart", "com.instacart.shopper", "Instacart Shopper", "IC", "Instacart"),
    WalmartSpark("walmart_spark", "com.walmart.spark", "Walmart Spark", "Spark", "Walmart Spark"),
    Unknown("_unknown", null, "Unknown", "", "Unknown"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }
        private val byName = entries.associateBy { it.name.lowercase(Locale.ROOT) }

        /**
         * Resolve the platform prefix from a rule id, or [Unknown].
         *
         * Note a deliberate divergence from `Ruleset.evalOrderByPlatform`'s
         * partition key (#435): `substringBefore('.')` with no default means a
         * DOTLESS id resolves the whole id as its wire (so a literal `"doordash"`
         * would resolve to [DoorDash]), whereas the Ruleset partition buckets a
         * dotless id under `""` — matching the old `startsWith("$wire.")` filter,
         * which never matched a dotless id. Real rule ids are always
         * `<wire>.<section>.<name>`, so the divergence is unreachable in practice;
         * it is documented so neither side is "fixed" to match the other blindly.
         */
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

        /**
         * THE answer to "which platforms does this build read" (#170), in registry order; each has a package. The
         * bound platforms ([bound]: they ship a ruleset), or every platform with a package when [includeUnbound]
         * (the build table's `readsUnboundPlatforms`, injected as `@ReadsUnboundPlatforms`).
         */
        fun readable(includeUnbound: Boolean): List<Platform> =
            entries.filter { it.packageName != null && (includeUnbound || it.bound) }

        /** The package names of [readable]: the OS-level event subscription. */
        fun readablePackages(includeUnbound: Boolean): Set<String> =
            readable(includeUnbound).mapNotNull { it.packageName }.toSet()

        /** The platforms that ship a ruleset: [readable] without the unbound ones. */
        val boundPlatforms: List<Platform> = readable(includeUnbound = false)

        /** The bound platforms' package names: the subscription of every build that reads only those. */
        val watchedPackages: Set<String> = readablePackages(includeUnbound = false)

        /**
         * Packages of the platforms whose offers arrive as a system-layer overlay ([offerOverlay],
         * #1152). Derived — never a hand-kept list.
         */
        val overlayPackages: Set<String> =
            entries.filter { it.offerOverlay }.mapNotNull { it.packageName }.toSet()
    }
}
