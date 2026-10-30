package cloud.trotter.log.gig.ui.main.settings.garage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.feature.setup.wizard.cards.VehicleCard
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleAddScreen(onDone: () -> Unit, viewModel: VehicleAddViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val draft by viewModel.draft.state.collectAsStateWithLifecycle()
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
    val enabled = loaded && !busy && failure != GarageWriteFailure.UNREADABLE && failure != GarageWriteFailure.GONE
    Scaffold(topBar = {
        TopAppBar(title = {
            Text(stringResource(R.string.vehicle_edit_add_title))
        }, navigationIcon = {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_content_desc_back))
            }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
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
                enabled = enabled)
            GarageFailure(failure)
            Button(onClick = { viewModel.save(onDone) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.garage_add))
            }
        }
    }
}

/** Bound pasted text without splitting a supplementary Unicode character. */
internal fun String.cappedVehicleName(): String = substring(0,
    offsetByCodePoints(0, minOf(codePointCount(0, length), Vehicle.MAX_NAME_CODE_POINTS)))
