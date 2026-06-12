package cloud.trotter.dashbuddy.state.effects

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber

/**
 * Handles the offer notification's **Accept / Decline** action buttons. Each fires the same
 * `UiInput` intent the bubble buttons do → EffectMap → `PerformRuleAction` (aimed by the offer
 * rule's bound target, #425) → verified accessibility click — so the dasher can act straight
 * from the heads-up notification without opening the bubble (which can't auto-expand from the
 * background; see #110 field test 2026-06-09).
 *
 * Uses [EntryPointAccessors] rather than `@AndroidEntryPoint` so we don't need the Hilt
 * `super.onReceive()` call (which doesn't compile against the abstract receiver in Kotlin).
 */
class OfferActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun stateManager(): StateManagerV2
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        Timber.i("OfferActionReceiver: %s", action)
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        deps.stateManager().dispatch(
            Observation.UiInput(timestamp = System.currentTimeMillis(), action = action),
        )
    }

    companion object {
        const val ACTION = "cloud.trotter.dashbuddy.action.OFFER_ACTION"
        const val EXTRA_ACTION = "offer_action"
    }
}
