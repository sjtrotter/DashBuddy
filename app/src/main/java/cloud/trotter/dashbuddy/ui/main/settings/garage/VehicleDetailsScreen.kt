package cloud.trotter.dashbuddy.ui.main.settings.garage

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.components.economy.EconomyEditor
import cloud.trotter.dashbuddy.ui.components.economy.VehicleMpgAndFuelInputs
import cloud.trotter.dashbuddy.ui.components.vehicle.displayName
import cloud.trotter.dashbuddy.ui.components.vehicle.vehicleTypeLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleDetailsScreen(
    onDone: () -> Unit,
    onAddVehicle: () -> Unit,
    viewModel: VehicleDetailsViewModel = hiltViewModel(),
) {
    val vehicle by viewModel.vehicle.collectAsStateWithLifecycle()
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val economy by viewModel.economy.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val retired by viewModel.retired.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    var retiring by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var leaveFailure by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<(() -> Unit)?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val enabled = vehicle != null && !leaving && failure != GarageWriteFailure.UNREADABLE && failure != GarageWriteFailure.GONE
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.retireLocked.collect { Toast.makeText(context, R.string.hud_vehicle_locked, Toast.LENGTH_SHORT).show() }
    }
    // Remove the inputs first. Their disposal commits run before this effect awaits the queue.
    LaunchedEffect(leaving, attempt) {
        if (leaving) {
            if (viewModel.awaitIdle() == GarageWriteFailure.NONE) destination?.invoke()
            else { leaveFailure = true; leaving = false }
        }
    }
    val leave: (() -> Unit) -> Unit = { next ->
        if (!leaving) { destination = next; leaving = true }
    }
    BackHandler { leave(onDone) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(vehicle?.displayName() ?: stringResource(R.string.vehicle_details_title)) },
            navigationIcon = {
                IconButton(onClick = { leave(onDone) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_content_desc_back))
                }
            })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            vehicle?.let { target ->
                val identity = listOfNotNull(target.profile.year, target.profile.make, target.profile.model)
                    .filter { it.isNotBlank() }.joinToString(" ")
                val type = stringResource(vehicleTypeLabel(target.profile.vehicleType))
                Text(if (identity.isBlank()) type else stringResource(R.string.vehicle_details_identity, identity, type),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (!retired) {
                Text(stringResource(R.string.vehicle_details_identity_hint), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { leave(onAddVehicle) }, enabled = enabled) { Text(stringResource(R.string.garage_add)) }
                if (!leaving) {
                    DisposableEffect(viewModel) { onDispose { viewModel.flushName() } }
                    OutlinedTextField(value = name, onValueChange = viewModel::setName,
                        label = { Text(stringResource(R.string.vehicle_edit_name)) },
                        supportingText = {
                            val count = name.codePointCount(0, name.length)
                            Text(stringResource(if (count >= Vehicle.MAX_NAME_CODE_POINTS - 5)
                                R.string.vehicle_edit_name_counter else R.string.vehicle_edit_name_hint,
                                count, Vehicle.MAX_NAME_CODE_POINTS))
                        }, singleLine = true, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().onFocusChanged { viewModel.nameFocusChanged(it.isFocused) })
                }
            }
            facts?.let { VehicleFacts(it, onEditCosts = null) }
            if (!retired && !leaving) economy?.let { eco ->
                Text(stringResource(R.string.vehicle_details_costs), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp))
                if (EconomyField.VEHICLE_MPG in EconomyFieldGroups.fieldsFor(eco.vehicleProfile)) {
                    VehicleMpgAndFuelInputs(eco.vehicleMpg, requireNotNull(vehicle?.profile?.gasFuel),
                        viewModel::setMpg, viewModel::setFuelType, enabled = enabled)
                }
                EconomyEditor(economy = eco,
                    onTiresChange = viewModel::setTires, onOilChange = viewModel::setOil,
                    onBrakesChange = viewModel::setBrakes, onFluidsChange = viewModel::setFluids,
                    onMiscChange = viewModel::setMiscRepairs, onDepreciationIncludeChange = viewModel::setDepreciationInclude,
                    onDepreciationValuesChange = viewModel::setDepreciationValues,
                    onInsuranceChange = viewModel::setInsurance, onRegistrationChange = viewModel::setRegistration,
                    onPhoneChange = { _, _, _ -> }, onExpectedAnnualMilesChange = viewModel::setExpectedAnnualMiles,
                    onChainChange = viewModel::setChain, onBatteryChange = viewModel::setBattery,
                    onBeltChange = viewModel::setBelt, onConsumptionChange = viewModel::setConsumption,
                    onElectricityPriceChange = viewModel::setElectricityPrice, showShared = false, enabled = enabled)
            }
            if (pending > 0) Text(stringResource(R.string.vehicle_details_saving), style = MaterialTheme.typography.bodySmall)
            if (failure == GarageWriteFailure.RETRY) {
                Text(stringResource(R.string.vehicle_details_not_saved), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::retry, enabled = !leaving) { Text(stringResource(R.string.vehicle_details_retry)) }
            } else GarageFailure(failure)
            if (retired) {
                Button(onClick = { viewModel.restore(onDone) }, enabled = enabled && pending == 0,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vehicle_restore)) }
            } else {
                OutlinedButton(onClick = { retiring = true }, enabled = enabled && pending == 0,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.garage_retire_vehicle_button)) }
            }
        }
    }
    if (leaveFailure) AlertDialog(
        onDismissRequest = { leaveFailure = false },
        text = { Text(stringResource(R.string.vehicle_details_unsaved)) },
        confirmButton = { TextButton(onClick = {
            leaveFailure = false
            viewModel.retry()
            attempt++
            leaving = true
        }) { Text(stringResource(R.string.vehicle_details_retry)) } },
        dismissButton = { TextButton(onClick = {
            leaveFailure = false
            scope.launch { viewModel.discardChanges(); destination?.invoke() }
        }) {
            Text(stringResource(R.string.vehicle_details_discard))
        } },
    )
    if (retiring) vehicle?.let { target ->
        AlertDialog(onDismissRequest = { retiring = false },
            text = { Text(stringResource(R.string.garage_retire_confirmation, target.displayName())) },
            dismissButton = { TextButton(onClick = { retiring = false }) { Text(stringResource(R.string.garage_cancel)) } },
            confirmButton = { TextButton(enabled = enabled, onClick = { retiring = false; viewModel.retire(onDone) }) {
                Text(stringResource(R.string.garage_retire))
            } })
    }
}
