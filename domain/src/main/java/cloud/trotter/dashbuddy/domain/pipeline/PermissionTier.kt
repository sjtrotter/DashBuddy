package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Permission tiers for effect verbs.
 *
 * Each [EffectVerb] declares the tier required to execute it. The side-effect
 * engine checks the user's granted tiers before dispatching. Grants are
 * persisted so users are not re-prompted on rule reload.
 */
enum class PermissionTier {
    /** No special permission required. */
    NONE,

    /** Requires accessibility service access (UI interaction, screenshots). */
    ACCESSIBILITY,

    /** Requires location access (odometer tracking). */
    LOCATION,

    /** Requires audio output (TTS). */
    AUDIO,
}
