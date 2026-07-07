package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields

/**
 * #240 — notification-driven effect diffs, extracted from [EffectMap] (past the #237 ceiling once
 * the #438 B-series grew it to ~1450 lines). `internal` extension on [EffectMap] (mirroring the
 * [OfferEffects]/[JobAcceptFlow] precedent), though this one is small enough that it never touches
 * `this` — kept as an extension anyway for stylistic consistency with the rest of the split. Pure
 * move: no behavior change.
 *
 * Handle notification-driven effects. These are global interceptors that apply regardless of
 * state. Intent-specific notification processing that can't be expressed as a JSON effect —
 * logging and other simple effects are declared in the rule JSON and handled by
 * [EffectMap.diffRuleEffects].
 */
internal fun EffectMap.diffNotification(obs: Observation): List<AppEffect> {
    if (obs !is Observation.Notification) return emptyList()
    val fields = obs.parsed as? ParsedFields.NotificationFields ?: return emptyList()

    return buildList {
        when (fields.intent) {
            "additional_tip" -> {
                val amount = fields.amount
                val storeName = fields.storeName
                val deliveredAt = fields.deliveredAt
                if (amount != null && storeName != null && deliveredAt != null) {
                    add(
                        AppEffect.ProcessTipNotification(
                            amount = amount,
                            storeName = storeName,
                            deliveredAt = deliveredAt,
                        )
                    )
                }
            }
        }
    }
}
