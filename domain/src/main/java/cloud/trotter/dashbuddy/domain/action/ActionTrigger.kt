package cloud.trotter.dashbuddy.domain.action

/**
 * Who initiated a [RuleAction] fire (#417/#422).
 *
 * Consent is asymmetric by design: a tap the dasher just asked for (a bubble
 * or own-notification button) IS the consent for that fire, while a tap the
 * app decided to make on its own must be covered by a persisted,
 * content-pinned capability grant (`RuleCapability.key`). The executor's
 * integrity checks — OS tier, package scope, label verification, throttle —
 * apply to BOTH; provenance never skips them.
 */
enum class ActionTrigger {
    /** The dasher explicitly requested this fire (HUD / own-notification tap). */
    USER,

    /** The app initiated this fire itself (e.g. the deferred expand tap). */
    AUTOMATION,
}
