package cloud.trotter.dashbuddy.feature.bubble.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.feature.bubble.R

/**
 * The manual platform switcher (#867) — one chip per live dash, the displayed one highlighted.
 *
 * Stateless and data-in/lambdas-out: the caller decides WHEN it renders (it passes an empty list
 * to hide it — fewer than two live sessions, or merged mode) and what a tap means. Tapping a chip
 * only ever dispatches [onSelect]; the ViewModel owns the resulting selection state.
 */
@Composable
fun PlatformSwitcherRow(
    platforms: List<Platform>,
    selected: Platform?,
    onSelect: (Platform) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (platforms.size < 2) return
    val c = AppTheme.colors
    val description = stringResource(R.string.bubble_switcher_content_desc)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.bubble_switcher_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        platforms.forEach { platform ->
            val isSelected = platform == selected
            AppChip(
                text = platform.shortName,
                color = if (isSelected) c.accent else c.neutral,
                container = if (isSelected) c.accent.copy(alpha = 0.15f) else c.neutralBg,
                modifier = Modifier.clickable { onSelect(platform) },
            )
        }
    }
}
