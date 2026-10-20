package cloud.trotter.dashbuddy.ui.main.settings.garage

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import cloud.trotter.dashbuddy.domain.model.vehicle.vehicleType
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.setup.wizard.cards.VehicleCard
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.ui.components.vehicle.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(onDone: () -> Unit, onEditCosts: () -> Unit, viewModel: VehicleEditViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val draft by viewModel.draft.state.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val retired by viewModel.retired.collectAsStateWithLifecycle()
    val isActive by viewModel.isActive.collectAsStateWithLifecycle()
    var retiring by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.retireLocked.collect {
            Toast.makeText(context, R.string.hud_vehicle_locked, Toast.LENGTH_SHORT).show()
        }
    }
    val enabled = loaded && !busy && failure != GarageWriteFailure.UNREADABLE && failure != GarageWriteFailure.GONE
    Scaffold(topBar = {
        TopAppBar(title = {
            Text(stringResource(if (viewModel.isEditing) R.string.vehicle_edit_title else R.string.vehicle_edit_add_title))
        }, navigationIcon = {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_content_desc_back))
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            facts?.let {
                VehicleFacts(it, onEditCosts = if (isActive && !retired) onEditCosts else null)
                Spacer(Modifier.height(16.dp))
            }
            if (retired) {
                GarageFailure(failure)
                Button(onClick = { viewModel.restore(onDone) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vehicle_restore))
                }
            } else {
                OutlinedTextField(value = name, onValueChange = { viewModel.setName(it.cappedVehicleName()) },
                    label = { Text(stringResource(R.string.vehicle_edit_name)) },
                    supportingText = {
                        val count = name.codePointCount(0, name.length)
                        Text(stringResource(if (count >= Vehicle.MAX_NAME_CODE_POINTS - 5) {
                            R.string.vehicle_edit_name_counter
                        } else R.string.vehicle_edit_name_hint, count, Vehicle.MAX_NAME_CODE_POINTS))
                    },
                    singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth())
                VehicleCard(step = WizardStep.VEHICLE, draft = draft, actions = viewModel.draft,
                    onAllowVehicleLookup = viewModel::allowLookup, showStepHeader = false, scrollable = false,
                    enabled = enabled, typeNote = {
                        if (viewModel.resetsCosts(draft.profile.vehicleType)) {
                            Text(stringResource(R.string.vehicle_edit_type_resets_costs),
                                style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text2)
                        }
                    })
                GarageFailure(failure)
                Button(onClick = { viewModel.save(onDone) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vehicle_edit_save))
                }
                if (viewModel.isEditing) {
                    OutlinedButton(onClick = { retiring = true }, enabled = enabled,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.garage_retire_vehicle_button))
                    }
                }
            }
        }
    }
    if (retiring) vehicle?.let { target ->
        AlertDialog(
            onDismissRequest = { retiring = false },
            text = { Text(stringResource(R.string.garage_retire_confirmation, target.displayName())) },
            dismissButton = {
                TextButton(onClick = { retiring = false }) { Text(stringResource(R.string.garage_cancel)) }
            },
            confirmButton = {
                TextButton(enabled = enabled, onClick = { retiring = false; viewModel.retire(onDone) }) {
                    Text(stringResource(R.string.garage_retire))
                }
            },
        )
    }
}

/** Bound pasted text without splitting a supplementary Unicode character. */
internal fun String.cappedVehicleName(): String = substring(0,
    offsetByCodePoints(0, minOf(codePointCount(0, length), Vehicle.MAX_NAME_CODE_POINTS)))
