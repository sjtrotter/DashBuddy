package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Complete vocabulary of side-effect verbs the state machine can produce.
 *
 * Every effect — whether driven by rules or by built-in transition defaults —
 * is expressed as one of these verbs. Unknown verbs are rejected at rule
 * compile time via [fromWire].
 *
 * Rules cannot declare actuation (#425): the former `click` verb was removed
 * from this vocabulary entirely. Rulesets expose target *bindings*; the
 * app-owned `RuleAction` registry decides and performs taps. Every verb here
 * is observational, app-internal, or lifecycle.
 *
 * @property wire The string used in rule JSON (`"screenshot"`, `"log"`, etc.)
 * @property tier The [PermissionTier] required to execute this verb.
 * @property hasDefault `true` if the state machine fires this verb automatically
 *   on certain transitions. Rules can override these defaults per-platform.
 */
enum class EffectVerb(
    val wire: String,
    val tier: PermissionTier,
    val hasDefault: Boolean,
) {
    // --- Observation-driven (rule-declared, no built-in default) ---
    SCREENSHOT("screenshot", PermissionTier.ACCESSIBILITY, hasDefault = false),
    BUBBLE("bubble", PermissionTier.NONE, hasDefault = false),
    LOG("log", PermissionTier.NONE, hasDefault = false),
    EVALUATE_OFFER("evaluate_offer", PermissionTier.NONE, hasDefault = false),
    SPEAK("speak", PermissionTier.AUDIO, hasDefault = false),

    // --- Lifecycle (built-in defaults on transitions, overridable by rules) ---
    SESSION_START("session_start", PermissionTier.NONE, hasDefault = true),
    SESSION_END("session_end", PermissionTier.NONE, hasDefault = true),
    ODOMETER_START("odometer_start", PermissionTier.LOCATION, hasDefault = true),
    ODOMETER_STOP("odometer_stop", PermissionTier.LOCATION, hasDefault = true),
    ODOMETER_PAUSE("odometer_pause", PermissionTier.LOCATION, hasDefault = true),
    ODOMETER_RESUME("odometer_resume", PermissionTier.LOCATION, hasDefault = true),
    SCHEDULE_TIMEOUT("schedule_timeout", PermissionTier.NONE, hasDefault = true),
    CANCEL_TIMEOUT("cancel_timeout", PermissionTier.NONE, hasDefault = true),
    ;

    companion object {
        private val byWire: Map<String, EffectVerb> = entries.associateBy { it.wire }

        /** Look up a verb by its wire name. Returns `null` for unknown verbs. */
        fun fromWire(wire: String): EffectVerb? = byWire[wire]
    }
}
