package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.bubble.session.PlatformSwitcherRow

@Composable
fun HudHead(platform: Platform?, switchable: List<Platform>, paused: Boolean, onSelectPlatform: (Platform) -> Unit, vehicleChip: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (switchable.size >= 2) PlatformSwitcherRow(switchable, platform, onSelectPlatform, Modifier.width(IntrinsicSize.Max))
            else Text(platform?.displayName.orEmpty(), style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.text2)
            if (paused) {
                // Separate nodes so the paused label reads (and is found) as itself.
                Text(" · ", style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.text3)
                Text(stringResource(R.string.hud_head_paused),
                    style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.warn)
            }
        }
        vehicleChip()
    }
}
