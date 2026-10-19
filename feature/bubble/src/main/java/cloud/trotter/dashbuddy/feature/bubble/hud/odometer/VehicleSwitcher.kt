package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun VehicleSwitcher(vehicles: List<Vehicle>, activeId: String?, saving: Boolean, onSelect: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit, onRetire: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OdometerTitle(stringResource(R.string.hud_odometer_vehicles), onBack)
        vehicles.forEach { vehicle ->
            val vehicleName = vehicle.displayName()
            Row(Modifier.fillMaxWidth().testTag("vehicle.${vehicle.id}").semantics { selected = vehicle.id == activeId }
                .clickable(enabled = !saving, role = Role.RadioButton) { onSelect(vehicle.id) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(vehicleName, Modifier.weight(1f), color = AppTheme.colors.text)
                if (vehicle.id == activeId) Text(stringResource(R.string.hud_odometer_active), color = AppTheme.colors.accent)
                IconButton(onClick = { onRetire(vehicle.id) }, enabled = !saving) {
                    Icon(Icons.Outlined.Delete, stringResource(R.string.hud_odometer_remove_vehicle, vehicleName), tint = AppTheme.colors.text2)
                }
            }
        }
        TextButton(onClick = onAdd, enabled = !saving, modifier = Modifier.testTag("vehicle.add")) {
            Text(stringResource(R.string.hud_odometer_add_vehicle))
        }
    }
}

@Composable
internal fun AddVehicleForm(name: String, onNameChange: (String) -> Unit, enabled: Boolean, onBack: () -> Unit, onAdd: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
        OdometerTitle(stringResource(R.string.hud_odometer_add_vehicle), onBack)
        OutlinedTextField(name, { if (it.codePointCount(0, it.length) <= Vehicle.MAX_NAME_CODE_POINTS) onNameChange(it) }, singleLine = true, enabled = enabled,
            label = { Text(stringResource(R.string.hud_odometer_vehicle_name)) },
            modifier = Modifier.fillMaxWidth().testTag("vehicle.name"))
        Button(onClick = { onAdd(name.trim()) }, enabled = name.trim().isNotEmpty() && enabled,
            modifier = Modifier.fillMaxWidth().testTag("vehicle.save")) {
            Text(if (name.isBlank()) stringResource(R.string.hud_odometer_add_vehicle)
                else stringResource(R.string.hud_odometer_add_named, name.trim()))
        }
    }
}
