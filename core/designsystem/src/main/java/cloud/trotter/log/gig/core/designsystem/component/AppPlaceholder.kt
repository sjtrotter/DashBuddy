package cloud.trotter.log.gig.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.R
import cloud.trotter.log.gig.core.designsystem.motion.rememberReducedMotion
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme

/** The pulse's two alpha ends and its half-period. */
private const val PULSE_LOW = 0.45f
private const val PULSE_HIGH = 1f
private const val PULSE_HALF_MS = 900

/**
 * A grey block standing in for a figure before its data arrives: the size the caller gives in
 * [modifier] (the shape of the figure it replaces), in the `surface3` role, pulsing gently on the
 * Compose frame clock. Under reduced motion it holds still. TalkBack reads [contentDescription]
 * ("Loading" by default); pass null when a parent announces the loading state once.
 */
@Composable
fun AppPlaceholder(
    modifier: Modifier = Modifier,
    contentDescription: String? = stringResource(R.string.app_placeholder_loading),
) {
    val alpha = rememberPlaceholderAlpha()
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha.value }
            .clip(MaterialTheme.shapes.extraSmall)
            .background(AppTheme.colors.surface3)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    )
}

/** A placeholder for one line of text: full height of a body line, [widthFraction] of the row. */
@Composable
fun AppPlaceholderLine(
    widthFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    contentDescription: String? = stringResource(R.string.app_placeholder_loading),
) {
    AppPlaceholder(modifier.fillMaxWidth(widthFraction).height(height), contentDescription)
}

/**
 * The placeholder pulse: an alpha between [PULSE_LOW] and [PULSE_HIGH] on the frame clock, or a
 * constant [PULSE_HIGH] under reduced motion. Public so a test can read it.
 */
@Composable
fun rememberPlaceholderAlpha(): State<Float> {
    if (rememberReducedMotion()) return remember { mutableFloatStateOf(PULSE_HIGH) }
    return rememberInfiniteTransition(label = "placeholder").animateFloat(
        initialValue = PULSE_HIGH,
        targetValue = PULSE_LOW,
        animationSpec = infiniteRepeatable(tween(PULSE_HALF_MS), RepeatMode.Reverse),
        label = "placeholderAlpha",
    )
}
