package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun Dock(model: DockModel, onClick: (DockKind) -> Unit) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        model.items.forEach { item ->
            val ink = when (item.state) {
                DockItemState.NORMAL -> c.text2
                DockItemState.LIT -> c.accent
                DockItemState.ACCENT -> c.accentText
                DockItemState.DISABLED -> c.text3
            }
            val background = when (item.state) {
                DockItemState.ACCENT -> c.accent
                DockItemState.LIT -> c.accentDim
                else -> c.surface2
            }
            val (label, icon) = when (item.kind) {
                DockKind.CASH_TIP -> R.string.hud_dock_cash_tip to Icons.Outlined.Payments
                DockKind.EXPENSE -> R.string.hud_dock_expense to Icons.Outlined.Receipt
                DockKind.LOG -> R.string.hud_dock_log to Icons.AutoMirrored.Outlined.List
                DockKind.SETTINGS -> R.string.hud_dock_settings to Icons.Outlined.Settings
            }
            Column(Modifier.weight(1f).height(64.dp).testTag("hud.dock.${item.kind}")
                .semantics { selected = item.state == DockItemState.LIT }
                .clip(RoundedCornerShape(16.dp)).background(background)
                .clickable(enabled = item.state != DockItemState.DISABLED, role = Role.Button) { onClick(item.kind) },
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(24.dp))
                Text(stringResource(label), color = ink, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
