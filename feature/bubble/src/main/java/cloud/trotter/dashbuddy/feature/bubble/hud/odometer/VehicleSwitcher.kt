package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
fun VehicleSwitcher(vehicles: List<Vehicle>, activeId: String?, saving: Boolean, onSelect: (String) -> Unit, onAdd: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        OdometerTitle(stringResource(R.string.hud_odometer_vehicles), onBack)
        vehicles.forEach { vehicle ->
            Row(Modifier.fillMaxWidth().testTag("vehicle.${vehicle.id}").semantics { selected = vehicle.id == activeId }
                .clickable(enabled = !saving, role = Role.RadioButton) { onSelect(vehicle.id) }.padding(vertical = 12.dp)) {
                Text(vehicle.name, Modifier.weight(1f), color = AppTheme.colors.text)
                if (vehicle.id == activeId) Text(stringResource(R.string.hud_odometer_active), color = AppTheme.colors.accent)
            }
        }
        TextButton(onClick = onAdd, enabled = !saving, modifier = Modifier.testTag("vehicle.add")) {
            Text(stringResource(R.string.hud_odometer_add_vehicle))
        }
    }
}

@Composable
internal fun AddVehicleForm(saving: Boolean, onBack: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()) {
        OdometerTitle(stringResource(R.string.hud_odometer_add_vehicle), onBack)
        OutlinedTextField(name, { name = it.take(30) }, singleLine = true, enabled = !saving,
            label = { Text(stringResource(R.string.hud_odometer_vehicle_name)) },
            modifier = Modifier.fillMaxWidth().testTag("vehicle.name"))
        Button(onClick = { onAdd(name.trim()) }, enabled = name.trim().isNotEmpty() && !saving,
            modifier = Modifier.fillMaxWidth().testTag("vehicle.save")) {
            Text(if (name.isBlank()) stringResource(R.string.hud_odometer_add_vehicle)
                else stringResource(R.string.hud_odometer_add_named, name.trim()))
        }
    }
}
