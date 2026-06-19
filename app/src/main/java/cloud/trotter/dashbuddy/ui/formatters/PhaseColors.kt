package cloud.trotter.dashbuddy.ui.formatters

import androidx.compose.ui.graphics.Color
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.domain.state.PhaseColor

/**
 * Resolves a domain [PhaseColor] token to its concrete brand [Color] (#audit-12).
 *
 * The phase-presentation SSOT lives in pure-Kotlin `:domain` and names color
 * families as tokens; this is the single UI-side place that binds each token to
 * an `AppColors` field. The background (`*Bg`) variant is [phaseBg].
 */
fun PhaseColor.color(c: AppColors): Color = when (this) {
    PhaseColor.GOOD -> c.good
    PhaseColor.NEUTRAL -> c.neutral
    PhaseColor.OFFER -> c.stOffer
    PhaseColor.PICKUP -> c.stPickup
}

/** The `*Bg` (tinted background) variant of a [PhaseColor] family. */
fun PhaseColor.phaseBg(c: AppColors): Color = when (this) {
    PhaseColor.GOOD -> c.goodBg
    PhaseColor.NEUTRAL -> c.neutralBg
    PhaseColor.OFFER -> c.stOfferBg
    PhaseColor.PICKUP -> c.stPickupBg
}
