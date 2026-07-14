package cloud.trotter.dashbuddy.domain.state

/**
 * Wire identifiers for **effect-bearing** notification intents (#762 D1). The single source of
 * truth shared by: the ruleset notification classification (`NotificationFields.intent`), the
 * effect diff that consumes them (`EffectMap.diffNotification`), and the rule→state contract that
 * validates their prerequisite parse fields at compile time
 * ([cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract.EFFECT_INTENTS]).
 *
 * Only intents whose recognition drives an app effect live here. Every **other** notification
 * intent is **informational by construction** — a rule may emit any intent string it likes and the
 * effect layer simply ignores the ones it has no handler for. That asymmetry is deliberate and is
 * the OTA/data-driven-recognition contract (a future CDN ruleset must be free to introduce new
 * informational intents without an app change); the compiler therefore rejects a *known*
 * effect-bearing intent that omits its required fields but **never** rejects an unknown one.
 *
 * Keep the literal here and nowhere else: a raw `"additional_tip"` string compared against
 * `NotificationFields.intent` in `:core:state`/`:app` is the exact D1 drift this SSOT closes (the
 * literal previously lived hard-coded in `NotificationEffects`), guarded by
 * `NotificationIntentSsotGuardTest`.
 */
object NotificationIntent {
    /**
     * A tip added to an already-completed delivery (DoorDash: "added $X tip on a past STORE order
     * delivered at TIME"). Canonical wire value — kept as DoorDash's fielded string so the working
     * `doordash.notification.additional_tip` rule needs no rename. Consumed by
     * `EffectMap.diffNotification` → `AppEffect.ProcessTipNotification`, which requires the
     * `amount`/`storeName`/`deliveredAt` fields the contract mandates.
     */
    const val ADDITIONAL_TIP = "additional_tip"
}
