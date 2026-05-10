package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Transition triggers that the state machine fires automatically.
 *
 * Each trigger corresponds to a state-machine event (mode change, task
 * lifecycle, etc.). [TransitionDefaults] maps each trigger to a default
 * set of [EffectVerb]s that fire unless a rule explicitly overrides
 * them for the matching platform.
 *
 * @property wire The string used in rule JSON for transition overrides.
 */
enum class TransitionTrigger(val wire: String) {
    MODE_TO_ONLINE("mode:online"),
    MODE_TO_PAUSED("mode:paused"),
    MODE_TO_OFFLINE("mode:offline"),

    /** Offer accepted — new job begins. Fires per offer, even for add-ons. */
    JOB_START("job:start"),
    /** All active work done, returning to idle. Fires once. */
    JOB_COMPLETED("job:completed"),

    /** New task detected — any pickup or dropoff. Covers initial navigation. */
    TASK_START("task:start"),
    /** Arrived at task location (drives odometer pause). */
    TASK_ARRIVED("task:arrived"),
    /** Individual task completed. */
    TASK_COMPLETED("task:completed"),

    RESUME_FROM_PAUSE("resume:from_pause"),
    ;

    companion object {
        private val byWire: Map<String, TransitionTrigger> = entries.associateBy { it.wire }

        /** Look up a trigger by its wire name. Returns `null` for unknown triggers. */
        fun fromWire(wire: String): TransitionTrigger? = byWire[wire]
    }
}

/**
 * Built-in effect defaults for state transitions.
 *
 * These fire automatically unless a rule for the matching platform declares
 * an override for the same [TransitionTrigger]. An override **replaces** the
 * default set — it does not merge.
 *
 * Defined in `:domain` so rule authors can reference the defaults when
 * deciding whether to override.
 */
object TransitionDefaults {

    val defaults: Map<TransitionTrigger, List<EffectVerb>> = mapOf(
        TransitionTrigger.MODE_TO_ONLINE to listOf(
            EffectVerb.SESSION_START,
            EffectVerb.ODOMETER_START,
            EffectVerb.LOG,
        ),
        TransitionTrigger.MODE_TO_PAUSED to listOf(
            EffectVerb.SCHEDULE_TIMEOUT,
            EffectVerb.LOG,
            EffectVerb.BUBBLE,
        ),
        TransitionTrigger.MODE_TO_OFFLINE to listOf(
            EffectVerb.SESSION_END,
            EffectVerb.ODOMETER_STOP,
            EffectVerb.LOG,
        ),
        TransitionTrigger.JOB_START to listOf(
            EffectVerb.LOG,
        ),
        TransitionTrigger.JOB_COMPLETED to listOf(
            EffectVerb.LOG,
        ),
        TransitionTrigger.TASK_START to listOf(
            EffectVerb.ODOMETER_RESUME,
            EffectVerb.LOG,
            EffectVerb.BUBBLE,
        ),
        TransitionTrigger.TASK_ARRIVED to listOf(
            EffectVerb.ODOMETER_PAUSE,
            EffectVerb.LOG,
        ),
        TransitionTrigger.TASK_COMPLETED to listOf(
            EffectVerb.LOG,
        ),
        TransitionTrigger.RESUME_FROM_PAUSE to listOf(
            EffectVerb.CANCEL_TIMEOUT,
        ),
    )
}
