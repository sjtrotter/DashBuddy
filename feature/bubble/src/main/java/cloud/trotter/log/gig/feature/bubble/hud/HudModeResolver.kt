package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Mode

object HudModeResolver {
    fun resolve(
        flow: Flow, regionMode: Mode?, hasSession: Boolean, hasActiveCard: Boolean, offerCard: Boolean, logOpen: Boolean,
    ): HudMode = when {
        // OFFER only with the offer's own card to show: a HUD pinned to one platform suppresses another
        // platform's card (BubbleViewModel.cards), and a bare offer mode would be a blank panel.
        flow == Flow.OfferPresented && offerCard -> HudMode.OFFER
        // A dash ended from Paused leaves the stepper's mode Paused with no session; rest is "no session".
        (!hasSession || regionMode == null || regionMode == Mode.Offline) && !hasActiveCard -> HudMode.RESTING
        logOpen -> HudMode.LOG
        else -> HudMode.LIVE
    }
}
