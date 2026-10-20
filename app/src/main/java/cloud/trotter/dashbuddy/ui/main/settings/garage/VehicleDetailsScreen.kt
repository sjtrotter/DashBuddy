package cloud.trotter.dashbuddy.ui.main.settings.garage

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.ui.components.economy.*
import cloud.trotter.dashbuddy.ui.components.vehicle.displayName
import cloud.trotter.dashbuddy.ui.components.vehicle.vehicleTypeLabel
import kotlinx.coroutines.CoroutineStart
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
    val retired by viewModel.retired.collectAsStateWithLifecycle()
    val lastSaveUndo by viewModel.lastSaveUndo.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    DisposableEffect(viewModel) {
        viewModel.enterScreen()
        onDispose { viewModel.clearUndo() }
    }
    LastSaveUndoSnackbar(lastSaveUndo, snackbarHost, { viewModel.undo(it) }, viewModel::dismissUndo)
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    var retiring by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var leaveFailure by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<() -> Unit>({}) }
    var retryTransition by remember { mutableStateOf<() -> Unit>({}) }
    val enabled = vehicle != null && !busy && !leaveFailure && failure != GarageWriteFailure.UNREADABLE && failure != GarageWriteFailure.GONE
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.retireLocked.collect { Toast.makeText(context, R.string.hud_vehicle_locked, Toast.LENGTH_SHORT).show() }
    }
    val leave: (() -> Unit) -> Unit = { next ->
        if (!busy && !leaveFailure) {
            destination = next
            val action: () -> Unit = {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    if (viewModel.finishAndAwait() == GarageWriteFailure.NONE) { next(); viewModel.resumeEditing() }
                    else { leaveFailure = true; viewModel.resumeEditing() }
                }
            }
            retryTransition = action
            action()
        }
    }
    BackHandler { leave(onDone) }
    CompositionLocalProvider(LocalEditSession provides viewModel.edits) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }, topBar = {
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
                    val name = viewModel.nameField()
                    val finishName = viewModel.edits.finisher("NAME")
                    OutlinedTextField(value = name.text, onValueChange = viewModel::setName,
                        label = { Text(stringResource(R.string.vehicle_edit_name)) },
                        supportingText = {
                            val count = name.text.codePointCount(0, name.text.length)
                            Text(stringResource(if (count >= Vehicle.MAX_NAME_CODE_POINTS - 5)
                                R.string.vehicle_edit_name_counter else R.string.vehicle_edit_name_hint,
                                count, Vehicle.MAX_NAME_CODE_POINTS))
                        }, singleLine = true, enabled = enabled,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { finishName(); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth().onFocusChanged {
                            if (name.focused && !it.isFocused) finishName()
                            viewModel.edits.focus("NAME", it.isFocused)
                        })
                }
                facts?.let { VehicleFacts(it, onEditCosts = null) }
                if (!retired) vehicle?.let { target ->
                    VehicleOdometerSwitch(target.showsOdometer, enabled) { viewModel.setOdometerShown(it) }
                }
                if (!retired) economy?.let { eco ->
                    Text(stringResource(R.string.vehicle_details_costs), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 16.dp))
                    if (EconomyField.VEHICLE_MPG in EconomyFieldGroups.fieldsFor(eco.vehicleProfile)) {
                        VehicleMpgAndFuelInputs(eco.vehicleMpg, requireNotNull(vehicle?.profile?.gasFuel),
                            viewModel::setMpg, { viewModel.setFuelType(it) }, enabled = enabled)
                    }
                    EconomyEditor(economy = eco,
                        onTireCostChange = viewModel::setTireCost, onTireLifetimeChange = viewModel::setTireLifetime,
                        onOilCostChange = viewModel::setOilCost, onOilIntervalChange = viewModel::setOilInterval,
                        onBrakesCostChange = viewModel::setBrakesCost, onBrakesIntervalChange = viewModel::setBrakesInterval,
                        onFluidsCostChange = viewModel::setFluidsCost, onFluidsIntervalChange = viewModel::setFluidsInterval,
                        onMiscYearlyChange = viewModel::setMiscYearly, onMiscMilesChange = viewModel::setMiscMiles,
                        onDepreciationIncludeChange = { viewModel.setDepreciationInclude(it) },
                        onPurchasePriceChange = viewModel::setPurchasePrice, onLifetimeMilesChange = viewModel::setLifetimeMiles,
                        onInsuranceChange = viewModel::setInsurance, onRegistrationChange = viewModel::setRegistration,
                        onExpectedAnnualMilesChange = viewModel::setExpectedAnnualMiles,
                        onChainCostChange = viewModel::setChainCost, onChainLifetimeChange = viewModel::setChainLifetime,
                        onBatteryCostChange = viewModel::setBatteryCost, onBatteryLifetimeChange = viewModel::setBatteryLifetime,
                        onBeltCostChange = viewModel::setBeltCost, onBeltLifetimeChange = viewModel::setBeltLifetime,
                        onConsumptionChange = viewModel::setConsumption, showShared = false, enabled = enabled)
                    TextButton(enabled = enabled, onClick = { resetting = true }) { Text(stringResource(R.string.vehicle_details_reset)) }
                }
                if (pending > 0) Text(stringResource(R.string.vehicle_details_saving), style = MaterialTheme.typography.bodySmall)
                if (failure == GarageWriteFailure.RETRY) {
                    Text(stringResource(R.string.vehicle_details_not_saved), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retry, enabled = !busy) { Text(stringResource(R.string.vehicle_details_retry)) }
                } else GarageFailure(failure)
                if (retired) {
                    Button(onClick = { viewModel.restore(onDone) }, enabled = enabled && pending == 0,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.vehicle_restore)) }
                } else {
                    OutlinedButton(onClick = { retiring = true }, enabled = enabled,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.garage_retire_vehicle_button)) }
                }
            }
        }
    }
    if (leaveFailure) AlertDialog(
        onDismissRequest = { leaveFailure = false },
        text = { Text(stringResource(R.string.vehicle_details_unsaved)) },
        confirmButton = { TextButton(onClick = {
            leaveFailure = false; viewModel.retry(); retryTransition()
        }) { Text(stringResource(R.string.vehicle_details_retry)) } },
        dismissButton = { TextButton(onClick = {
            scope.launch { viewModel.discardChanges(); leaveFailure = false; destination() }
        }) { Text(stringResource(R.string.vehicle_details_discard)) } },
    )
    if (retiring) vehicle?.let { target ->
        AlertDialog(onDismissRequest = { retiring = false },
            text = { Text(stringResource(R.string.garage_retire_confirmation, target.displayName())) },
            dismissButton = { TextButton(onClick = { retiring = false }) { Text(stringResource(R.string.garage_cancel)) } },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                retiring = false
                destination = onDone
                val action = { viewModel.retire(onDone, { leaveFailure = true }) }
                retryTransition = action
                action()
            }) { Text(stringResource(R.string.garage_retire)) } })
    }
    if (resetting) AlertDialog(onDismissRequest = { resetting = false },
        text = { Text(stringResource(R.string.vehicle_details_reset_confirmation)) },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.garage_cancel)) } },
        confirmButton = { TextButton(onClick = {
            resetting = false
            destination = {}
            retryTransition = {
                scope.launch {
                    if (viewModel.awaitIdle() != GarageWriteFailure.NONE) leaveFailure = true
                    viewModel.resumeEditing()
                }
            }
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (viewModel.resetEstimates() != GarageWriteFailure.NONE) leaveFailure = true
            }
        }) { Text(stringResource(R.string.vehicle_details_reset)) } })
}
