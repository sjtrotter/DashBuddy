package cloud.trotter.dashbuddy.feature.bubble.hud.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun VehicleRow(model: VehicleRowModel, onSwap: () -> Unit, onAdd: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().height(44.dp).testTag("hud.vehicle.row")
            .background(colors.surface, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.DirectionsCar, contentDescription = null, tint = colors.text3,
            modifier = Modifier.size(20.dp))
        if (model.unreadable) {
            Text(stringResource(R.string.hud_odometer_vehicles_unreadable), color = colors.warn,
                style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.name ?: stringResource(R.string.hud_vehicle_none),
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (model.name == null) colors.text2 else colors.text,
                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                model.typeLabel?.let {
                    Text(it, color = colors.text3, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (model.canSwap) {
                IconButton(onClick = onSwap, modifier = Modifier.size(40.dp).testTag("hud.vehicle.swap")) {
                    Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.hud_vehicle_switch), tint = colors.accent)
                }
            }
            TextButton(onClick = onAdd, modifier = Modifier.testTag("hud.vehicle.add")) {
                Text(stringResource(R.string.hud_vehicle_add), color = colors.accent)
            }
        }
    }
}
