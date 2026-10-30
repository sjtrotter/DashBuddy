package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.state.PhaseColor
import cloud.trotter.log.gig.feature.bubble.formatters.color

@Composable
fun PhaseStrip(color: PhaseColor, resting: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(6.dp).background(if (resting) AppTheme.colors.rest else color.color(AppTheme.colors)))
}
