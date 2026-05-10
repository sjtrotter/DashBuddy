package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Complete vocabulary of side-effect verbs the state machine can produce.
 *
 * Every effect — whether driven by rules or by built-in transition defaults —
 * is expressed as one of these verbs. Unknown verbs are rejected at rule
 * compile time via [fromWire].
 *
 * @property wire The string used in rule JSON (`"click"`, `"screenshot"`, etc.)
 * @property tier The [PermissionTier] required to execute this verb.
 * @property requiresTarget `true` if the verb operates on a UI node ([NodeRef]).
 * @property hasDefault `true` if the state machine fires this verb automatically
 *   on certain transitions. Rules can override these defaults per-platform.
 */
enum class EffectVerb(
    val wire: String,
    val tier: PermissionTier,
    val requiresTarget: Boolean,
    val hasDefault: Boolean,
) {
    // --- Observation-driven (rule-declared, no built-in default) ---
    CLICK("click", PermissionTier.ACCESSIBILITY, requiresTarget = true, hasDefault = false),
    SCREENSHOT("screenshot", PermissionTier.ACCESSIBILITY, requiresTarget = false, hasDefault = false),
    BUBBLE("bubble", PermissionTier.NONE, requiresTarget = false, hasDefault = false),
    LOG("log", PermissionTier.NONE, requiresTarget = false, hasDefault = false),
    EVALUATE_OFFER("evaluate_offer", PermissionTier.NONE, requiresTarget = false, hasDefault = false),
    SPEAK("speak", PermissionTier.AUDIO, requiresTarget = false, hasDefault = false),

    // --- Lifecycle (built-in defaults on transitions, overridable by rules) ---
    SESSION_START("session_start", PermissionTier.NONE, requiresTarget = false, hasDefault = true),
    SESSION_END("session_end", PermissionTier.NONE, requiresTarget = false, hasDefault = true),
    ODOMETER_START("odometer_start", PermissionTier.LOCATION, requiresTarget = false, hasDefault = true),
    ODOMETER_STOP("odometer_stop", PermissionTier.LOCATION, requiresTarget = false, hasDefault = true),
    ODOMETER_PAUSE("odometer_pause", PermissionTier.LOCATION, requiresTarget = false, hasDefault = true),
    ODOMETER_RESUME("odometer_resume", PermissionTier.LOCATION, requiresTarget = false, hasDefault = true),
    SCHEDULE_TIMEOUT("schedule_timeout", PermissionTier.NONE, requiresTarget = false, hasDefault = true),
    CANCEL_TIMEOUT("cancel_timeout", PermissionTier.NONE, requiresTarget = false, hasDefault = true),
    ;

    companion object {
        private val byWire: Map<String, EffectVerb> = entries.associateBy { it.wire }

        /** Look up a verb by its wire name. Returns `null` for unknown verbs. */
        fun fromWire(wire: String): EffectVerb? = byWire[wire]
    }
}
