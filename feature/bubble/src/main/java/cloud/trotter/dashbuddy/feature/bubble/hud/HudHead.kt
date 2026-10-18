package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.Box
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
    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
        if (switchable.size >= 2) {
            PlatformSwitcherRow(switchable, platform, onSelectPlatform)
        } else {
            val name = platform?.displayName.orEmpty()
            Text(if (paused) stringResource(R.string.hud_head_paused, name) else name, style = MaterialTheme.typography.labelLarge, color = AppTheme.colors.text2)
        }
    }
}
