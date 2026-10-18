package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode

object HudModeResolver {
    fun resolve(flow: Flow, regionMode: Mode?, hasActiveCard: Boolean, logOpen: Boolean): HudMode = when {
        flow == Flow.OfferPresented -> HudMode.OFFER
        (regionMode == null || regionMode == Mode.Offline) && !hasActiveCard -> HudMode.RESTING
        logOpen -> HudMode.LOG
        else -> HudMode.LIVE
    }
}
