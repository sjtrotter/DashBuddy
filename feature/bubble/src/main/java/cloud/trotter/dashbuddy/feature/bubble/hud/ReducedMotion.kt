package cloud.trotter.dashbuddy.feature.bubble.hud

import android.animation.ValueAnimator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

val LocalReducedMotion = staticCompositionLocalOf<Boolean?> { null }

@Composable
fun rememberReducedMotion(): Boolean = LocalReducedMotion.current
    ?: remember { !ValueAnimator.areAnimatorsEnabled() }
