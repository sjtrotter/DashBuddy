package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/** The motion a route change gets (#1524). */
enum class ScreenTransition { FADE_THROUGH, FORWARD, BACK, NONE }

/**
 * Which motion a route change gets. Pure; the NavHost maps the result to Compose specs.
 * Reduced motion turns every change off; tab root to tab root fades through; anything else is a
 * shared-axis slide, forward on a push and back on a pop. A null route (the start) is not a tab root.
 */
fun screenTransition(fromRoute: String?, toRoute: String?, isPop: Boolean, reducedMotion: Boolean): ScreenTransition =
    when {
        reducedMotion -> ScreenTransition.NONE
        TopLevel.fromRoute(fromRoute) != null && TopLevel.fromRoute(toRoute) != null -> ScreenTransition.FADE_THROUGH
        isPop -> ScreenTransition.BACK
        else -> ScreenTransition.FORWARD
    }

/** Material fade through: the outgoing screen fades first, then the incoming one fades and scales in. */
private const val FADE_THROUGH_OUT_MS = 90
private const val FADE_THROUGH_IN_MS = 210
private const val FADE_THROUGH_INITIAL_SCALE = 0.92f

/** The shared-axis slide covers a tenth of the width. Material 3 1.4 keeps its motion scheme internal, so plain tweens. */
private const val SHARED_AXIS_DIVISOR = 10
private const val SHARED_AXIS_MS = 300
private fun <T> sharedAxis() = tween<T>(SHARED_AXIS_MS, easing = FastOutSlowInEasing)

/** The incoming screen's motion for this transition. */
fun ScreenTransition.enter(): EnterTransition = when (this) {
    ScreenTransition.FADE_THROUGH ->
        fadeIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing)) +
            scaleIn(
                initialScale = FADE_THROUGH_INITIAL_SCALE,
                animationSpec = tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing),
            )
    ScreenTransition.FORWARD ->
        slideInHorizontally(sharedAxis()) { it / SHARED_AXIS_DIVISOR } + fadeIn(sharedAxis())
    ScreenTransition.BACK ->
        slideInHorizontally(sharedAxis()) { -it / SHARED_AXIS_DIVISOR } + fadeIn(sharedAxis())
    ScreenTransition.NONE -> EnterTransition.None
}

/** The outgoing screen's motion for this transition. */
fun ScreenTransition.exit(): ExitTransition = when (this) {
    ScreenTransition.FADE_THROUGH -> fadeOut(tween(FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing))
    ScreenTransition.FORWARD ->
        slideOutHorizontally(sharedAxis()) { -it / SHARED_AXIS_DIVISOR } + fadeOut(sharedAxis())
    ScreenTransition.BACK ->
        slideOutHorizontally(sharedAxis()) { it / SHARED_AXIS_DIVISOR } + fadeOut(sharedAxis())
    ScreenTransition.NONE -> ExitTransition.None
}
