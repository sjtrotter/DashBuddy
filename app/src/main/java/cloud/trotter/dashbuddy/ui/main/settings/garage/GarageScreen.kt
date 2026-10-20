package cloud.trotter.dashbuddy.ui.main.settings.garage

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.components.vehicle.vehicleTypeLabel
import cloud.trotter.dashbuddy.ui.components.vehicle.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: GarageViewModel = hiltViewModel(),
) {
    val garage by viewModel.garage.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.garage_title)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_content_desc_back))
            }
        })
    }) { padding ->
        Box(Modifier.padding(padding)) {
            GarageList(garage, busy, failure, onSelect = { id ->
                when {
                    id == garage?.active?.id -> Unit
                    viewModel.swapLocked() -> Toast.makeText(context, R.string.hud_vehicle_locked, Toast.LENGTH_SHORT).show()
                    else -> viewModel.select(id)
                }
            }, onEdit = onEdit, onAdd = onAdd)
        }
    }
}

@Composable
fun GarageList(
    garage: Garage?,
    busy: Boolean,
    failure: GarageWriteFailure,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val unreadable = garage?.readable == false || failure == GarageWriteFailure.UNREADABLE
    val enabled = garage != null && !unreadable && !busy
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        LazyColumn(Modifier.weight(1f).selectableGroup()) {
            if (garage != null && !unreadable) {
                if (garage.unretired.isEmpty()) item { Text(stringResource(R.string.garage_empty)) }
                items(garage.unretired, key = { it.id }) { vehicle ->
                    val name = vehicle.displayName()
                    val type = stringResource(vehicleTypeLabel(vehicle.profile.vehicleType))
                    val identity = if (vehicle.name != null && vehicle.profile.hasCarIdentity) {
                        listOfNotNull(vehicle.profile.year, vehicle.profile.make, vehicle.profile.model)
                            .filter { it.isNotBlank() }.joinToString(" ")
                    } else ""
                    val selected = garage.active?.id == vehicle.id
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).selectable(selected = selected, enabled = enabled,
                                role = Role.RadioButton, onClick = { onSelect(vehicle.id) }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null, enabled = enabled)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleMedium)
                                Text(if (identity.isBlank()) type else stringResource(R.string.garage_vehicle_details, type, identity),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        VerticalDivider(
                            modifier = Modifier.padding(horizontal = 8.dp).height(32.dp)
                                .testTag("garage.vehicle.divider.${vehicle.id}"),
                            thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        IconButton(onClick = { onEdit(vehicle.id) }, enabled = enabled) {
                            Icon(Icons.Outlined.Settings, stringResource(R.string.garage_vehicle_settings, name))
                        }
                    }
                }
            }
            item { GarageFailure(if (unreadable) GarageWriteFailure.UNREADABLE else failure) }
        }
        Text(stringResource(R.string.garage_active_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.garage_add))
        }
    }
}

@Composable
internal fun GarageFailure(failure: GarageWriteFailure) {
    val message = when (failure) {
        GarageWriteFailure.NONE -> return
        GarageWriteFailure.RETRY -> R.string.garage_save_failed
        GarageWriteFailure.UNREADABLE -> R.string.garage_vehicles_unreadable
        GarageWriteFailure.GONE -> R.string.garage_vehicle_gone
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 8.dp))
}
