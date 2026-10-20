package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import cloud.trotter.dashbuddy.R

@Composable
fun VehicleOdometerSwitch(shown: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.vehicle_odometer_title)) },
        supportingContent = { Text(stringResource(R.string.vehicle_odometer_summary)) },
        trailingContent = { Switch(checked = shown, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = shown, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}
