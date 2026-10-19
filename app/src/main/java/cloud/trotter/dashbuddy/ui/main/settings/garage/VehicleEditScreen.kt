package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.setup.wizard.cards.VehicleCard
import cloud.trotter.dashbuddy.feature.setup.wizard.model.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(onDone: () -> Unit, viewModel: VehicleEditViewModel = hiltViewModel()) {
    val name by viewModel.name.collectAsStateWithLifecycle()
    val draft by viewModel.draft.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val failure by viewModel.failure.collectAsStateWithLifecycle()
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
            OutlinedTextField(value = name, onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.vehicle_edit_name)) },
                supportingText = { Text(stringResource(R.string.vehicle_edit_name_hint)) },
                singleLine = true, enabled = !busy && failure != GarageWriteFailure.UNREADABLE,
                modifier = Modifier.fillMaxWidth())
            VehicleCard(step = WizardStep.VEHICLE, draft = draft, actions = viewModel.draft,
                onAllowVehicleLookup = viewModel::allowLookup, showStepHeader = false, scrollable = false)
            GarageFailure(failure)
            Button(onClick = { viewModel.save(onDone) }, enabled = !busy && failure != GarageWriteFailure.UNREADABLE,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vehicle_edit_save))
            }
        }
    }
}
