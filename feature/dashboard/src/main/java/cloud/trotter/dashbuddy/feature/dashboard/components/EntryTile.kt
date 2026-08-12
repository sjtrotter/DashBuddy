package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme

/**
 * One destination tile.
 *
 * **Sized for four across (#1024 D5).** Home puts Analytics · Playbook · Ratings · Settings in a
 * single row, which is roughly 78 dp per tile on a 360 dp device: at `titleMedium` the longer labels
 * wrap while the shorter ones don't, and the row goes ragged — worse at a large font scale. So the
 * label is `titleSmall`, capped at [LABEL_MAX_LINES] and ellipsized rather than allowed to grow the
 * tile, and the caller pins the row to `IntrinsicSize.Min` so all four tiles share one height
 * whatever their labels do.
 */
@Composable
fun EntryTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(modifier = modifier.clickable(onClick = onClick)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AppTheme.colors.accent,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = AppTheme.colors.text,
            maxLines = LABEL_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Two lines is enough for every destination name at a large font scale; a third would tower. */
private const val LABEL_MAX_LINES = 2
