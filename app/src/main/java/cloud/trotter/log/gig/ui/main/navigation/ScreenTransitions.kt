package cloud.trotter.log.gig.ui.main.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

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

/** The shared-axis slide covers a tenth of the width. */
private const val SHARED_AXIS_DIVISOR = 10

/** The two specs the shared-axis slide takes from Material's motion scheme. */
class SharedAxisSpecs(val spatial: FiniteAnimationSpec<IntOffset>, val effects: FiniteAnimationSpec<Float>)

/** The incoming screen's motion for this transition. */
fun ScreenTransition.enter(specs: SharedAxisSpecs): EnterTransition = when (this) {
    ScreenTransition.FADE_THROUGH ->
        fadeIn(tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing)) +
            scaleIn(
                initialScale = FADE_THROUGH_INITIAL_SCALE,
                animationSpec = tween(FADE_THROUGH_IN_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = LinearOutSlowInEasing),
            )
    ScreenTransition.FORWARD ->
        slideInHorizontally(specs.spatial) { it / SHARED_AXIS_DIVISOR } + fadeIn(specs.effects)
    ScreenTransition.BACK ->
        slideInHorizontally(specs.spatial) { -it / SHARED_AXIS_DIVISOR } + fadeIn(specs.effects)
    ScreenTransition.NONE -> EnterTransition.None
}

/** The outgoing screen's motion for this transition. */
fun ScreenTransition.exit(specs: SharedAxisSpecs): ExitTransition = when (this) {
    ScreenTransition.FADE_THROUGH -> fadeOut(tween(FADE_THROUGH_OUT_MS, easing = FastOutLinearInEasing))
    ScreenTransition.FORWARD ->
        slideOutHorizontally(specs.spatial) { -it / SHARED_AXIS_DIVISOR } + fadeOut(specs.effects)
    ScreenTransition.BACK ->
        slideOutHorizontally(specs.spatial) { it / SHARED_AXIS_DIVISOR } + fadeOut(specs.effects)
    ScreenTransition.NONE -> ExitTransition.None
}
