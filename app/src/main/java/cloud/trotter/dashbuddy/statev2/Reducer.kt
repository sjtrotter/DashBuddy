package cloud.trotter.dashbuddy.statev2

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.reducers.*
import cloud.trotter.dashbuddy.statev2.sidechannels.NotificationHandler

object Reducer {

    data class Transition(
        val newState: AppStateV2,
        val effects: List<AppEffect> = emptyList()
    )

    fun reduce(currentState: AppStateV2, context: StateContext): Transition {
        // --- 0. SIDE CHANNEL INTERCEPTORS (Notifications) ---
        if (context.notification != null) { // Check the new object
            val sideChannelTransition = NotificationHandler.handle(currentState, context)
            if (sideChannelTransition != null) {
                return sideChannelTransition
            }
        }

        val input = context.screenInfo ?: return Transition(currentState)
        if (input.screen == Screen.UNKNOWN) return Transition(currentState)

        // 1. SEQUENTIAL LOGIC (Specifics)
        // We now map EVERY state to its specific reducer
        val sequential = when (currentState) {
            is AppStateV2.Initializing -> InitializingReducer.reduce(currentState, input)
            is AppStateV2.IdleOffline -> IdleReducer.reduce(currentState, input, context)
            is AppStateV2.AwaitingOffer -> AwaitingReducer.reduce(currentState, input)
            is AppStateV2.OfferPresented -> OfferReducer.reduce(currentState, input)
            is AppStateV2.OnPickup -> PickupReducer.reduce(currentState, input)
            is AppStateV2.OnDelivery -> DeliveryReducer.reduce(currentState, input)
            is AppStateV2.PostDelivery -> PostDeliveryReducer.reduce(currentState, input)
            is AppStateV2.PostDash -> SummaryReducer.reduce(currentState, input)
            is AppStateV2.PausedOrInterrupted -> null // Let anchors catch us up
        }

        if (sequential != null) return sequential

        // 2. ANCHOR LOGIC (Global Fallback)
        val anchor = checkAnchors(currentState, input)

        if (anchor != null) {
            val warning =
                AppEffect.UpdateBubble("⚠️ State recovered via Anchor", isImportant = false)
            return anchor.copy(effects = anchor.effects + warning)
        }

        // 3. STASIS
        return Transition(currentState)
    }

    private fun checkAnchors(state: AppStateV2, input: ScreenInfo): Transition? {
        return when (input) {
            // Anchor: Searching
            is ScreenInfo.WaitingForOffer -> {
                if (state !is AppStateV2.AwaitingOffer)
                    AwaitingReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: New Offer
            is ScreenInfo.Offer -> {
                if (state !is AppStateV2.OfferPresented)
                    OfferReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Offline/Map
            is ScreenInfo.IdleMap -> {
                if (state !is AppStateV2.IdleOffline && state !is AppStateV2.Initializing)
                    IdleReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Pickup Screen
            is ScreenInfo.PickupDetails -> {
                if (state !is AppStateV2.OnPickup)
                    PickupReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Dropoff Screen
            is ScreenInfo.DropoffDetails -> {
                if (state !is AppStateV2.OnDelivery)
                    DeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: Payout Screen
            is ScreenInfo.DeliveryCompleted -> {
                // We use dedupe logic inside transitionTo, but generally if we aren't in PostDelivery, we force it.
                if (state !is AppStateV2.PostDelivery)
                    PostDeliveryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }
            // Anchor: End of Dash Summary
            is ScreenInfo.DashSummary -> {
                if (state !is AppStateV2.PostDash)
                    SummaryReducer.transitionTo(state, input, isRecovery = true)
                else null
            }

            else -> null
        }
    }
}