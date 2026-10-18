package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.session.PlatformSwitcherRow

@Composable
fun HudHead(platform: Platform?, switchable: List<Platform>, paused: Boolean, onSelectPlatform: (Platform) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            if (switchable.size >= 2) PlatformSwitcherRow(switchable, platform, onSelectPlatform)
            else Text(platform?.displayName.orEmpty(), style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.text2)
        }
        if (paused) Text(stringResource(R.string.hud_head_paused), Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.warn)
    }
}
