package cloud.trotter.dashbuddy.state.effects

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import cloud.trotter.dashbuddy.core.state.StateManagerV2
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
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
        val uiInput = uiInputFrom(intent) ?: return
        Timber.tag("Effects").i("OfferActionReceiver: %s on %s", uiInput.action, uiInput.targetPlatform?.wire ?: "?")
        // #457: dismiss the heads-up immediately — the dasher acted. (Offer resolution also fires
        // CancelOfferNotification, but cancel now so the banner/shade entry doesn't linger.)
        // #438 B4: dismiss ONLY this offer's banner (per-offer id from the tap's own carried hash),
        // not every offer heads-up — a concurrent/replacement offer keeps its banner.
        NotificationManagerCompat.from(context)
            .cancel(BubbleManager.offerNotificationId(uiInput.offerHash))
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        deps.stateManager().dispatch(uiInput)
    }

    companion object {
        const val ACTION = "cloud.trotter.dashbuddy.action.OFFER_ACTION"
        const val EXTRA_ACTION = "offer_action"
        const val EXTRA_PLATFORM = "offer_platform"
        const val EXTRA_OFFER_HASH = "offer_hash"

        /**
         * Map the broadcast extras to the dispatched [Observation.UiInput] (#438 item 8a). The acted
         * offer's identity (platform wire + offerHash) rides the PendingIntent extras so the input
         * targets the owning region — an Unknown-platform tap steps no region post-#682. Pure +
         * Hilt-free so the dispatch shape is unit-testable without the receiver's entry-point lookup.
         */
        fun uiInputFrom(intent: Intent): Observation.UiInput? {
            val action = intent.getStringExtra(EXTRA_ACTION) ?: return null
            return Observation.UiInput(
                timestamp = System.currentTimeMillis(),
                action = action,
                targetPlatform = intent.getStringExtra(EXTRA_PLATFORM)?.let(Platform::fromWire),
                offerHash = intent.getStringExtra(EXTRA_OFFER_HASH),
            )
        }
    }
}
