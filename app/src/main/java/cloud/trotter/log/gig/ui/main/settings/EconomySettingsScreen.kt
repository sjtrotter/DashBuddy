package cloud.trotter.log.gig.ui.main.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.ui.components.economy.*
import cloud.trotter.log.gig.ui.components.vehicle.displayName
import cloud.trotter.log.gig.ui.main.settings.garage.GarageWriteFailure
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Shared settings and a link to the active vehicle's sole live editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EconomySettingsScreen(
    onBack: () -> Unit,
    onOpenGarage: () -> Unit,
    onOpenVehicle: (String) -> Unit,
    viewModel: EconomySettingsViewModel = hiltViewModel(),
) {
    val activeVehicle by viewModel.activeVehicle.collectAsStateWithLifecycle()
    val eco by viewModel.userEconomy.collectAsStateWithLifecycle()
    val gasAuto by viewModel.gasAuto.collectAsStateWithLifecycle()
    val timeConstants by viewModel.timeConstants.collectAsStateWithLifecycle()
    val lastSaveUndo by viewModel.lastSaveUndo.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    DisposableEffect(viewModel) {
        viewModel.enterScreen()
        onDispose { viewModel.clearUndo() }
    }
    LastSaveUndoSnackbar(lastSaveUndo, snackbarHost, { viewModel.undo(it) }, viewModel::dismissUndo)
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var unsaved by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf<() -> Unit>({}) }
    var retryTransition by remember { mutableStateOf<() -> Unit>({}) }
    val scope = rememberCoroutineScope()
    val leave: (() -> Unit) -> Unit = { next ->
        if (!busy && !unsaved) {
            destination = next
            val action: () -> Unit = {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    if (viewModel.finishAndAwait() == GarageWriteFailure.NONE) { next(); viewModel.resumeEditing() }
                    else { unsaved = true; viewModel.resumeEditing() }
                }
            }
            retryTransition = action
            action()
        }
    }
    val enabled = !busy && !unsaved
    BackHandler { leave(onBack) }
    CompositionLocalProvider(LocalEditSession provides viewModel.edits) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHost) }, topBar = {
            TopAppBar(title = { Text(stringResource(R.string.economy_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { leave(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_content_desc_back))
                    }
                }, actions = {
                    TextButton(enabled = enabled, onClick = {
                        destination = {}
                        retryTransition = {
                            scope.launch {
                                if (viewModel.awaitIdle() != GarageWriteFailure.NONE) unsaved = true
                                viewModel.resumeEditing()
                            }
                        }
                        scope.launch(start = CoroutineStart.UNDISPATCHED) {
                            if (viewModel.resetDefaults() != GarageWriteFailure.NONE) unsaved = true
                        }
                    }) { Text(stringResource(R.string.economy_settings_reset_defaults)) }
                })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ListItem(
                    headlineContent = { Text(activeVehicle?.displayName() ?: stringResource(R.string.economy_settings_vehicle_empty)) },
                    supportingContent = activeVehicle?.let { { Text(Formats.money3(eco.operatingCostPerMile) + "/mi") } },
                    modifier = Modifier.clickable(enabled = enabled) {
                        val id = activeVehicle?.id
                        leave { if (id == null) onOpenGarage() else onOpenVehicle(id) }
                    },
                )
                Row {
                    Text(stringResource(R.string.economy_shared_auto_gas_price), Modifier.weight(1f))
                    Switch(checked = gasAuto, onCheckedChange = { viewModel.setGasAuto(it) }, enabled = enabled)
                }
                DecimalInput(stringResource(R.string.economy_shared_gas_price), eco.gasPricePerGallon, viewModel::setGasPrice,
                    enabled = enabled, fieldKey = "GAS_PRICE")
                DecimalInput(stringResource(R.string.vehicle_facts_electricity_price), eco.electricityPricePerKwh,
                    viewModel::setElectricityPrice, enabled = enabled, fieldKey = "ELECTRICITY_PRICE")
                SharedPhoneInputs(eco, viewModel::setPhoneTotal, viewModel::setPhoneLines,
                    { viewModel.setPhoneShare(it) }, enabled)
                EconomyTimeConstantsSection(timeConstants, viewModel::setTimeConstantOverride, enabled = enabled)
                if (failure == GarageWriteFailure.RETRY) {
                    Text(stringResource(R.string.vehicle_details_not_saved), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = viewModel::retry, enabled = !busy) { Text(stringResource(R.string.vehicle_details_retry)) }
                }
            }
        }
    }
    if (unsaved) AlertDialog(onDismissRequest = { unsaved = false },
        text = { Text(stringResource(R.string.vehicle_details_unsaved)) },
        confirmButton = { TextButton(onClick = {
            unsaved = false; viewModel.retry(); retryTransition()
        }) { Text(stringResource(R.string.vehicle_details_retry)) } },
        dismissButton = { TextButton(onClick = {
            scope.launch { viewModel.discardChanges(); unsaved = false; destination() }
        }) { Text(stringResource(R.string.vehicle_details_discard)) } })
}
