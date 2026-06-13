package cloud.trotter.dashbuddy.ui.main.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.ui.components.economy.EconomyEditor
import cloud.trotter.dashbuddy.ui.components.economy.TrueCostFooter
import cloud.trotter.dashbuddy.ui.components.economy.VehicleClassPicker

/**
 * Personal Economy settings. Chrome only: the Scaffold, intro copy, and the
 * reset action. The ten cost sections, vehicle class picker, and true-cost
 * footer are the shared [EconomyEditor] family (#357) — also used by the
 * setup wizard's ECONOMY_COSTS step. Everything below the screen level takes
 * data + lambdas, never the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EconomySettingsScreen(
    onBack: () -> Unit,
    viewModel: EconomySettingsViewModel = hiltViewModel(),
) {
    val eco by viewModel.userEconomy.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personal Economy") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetDefaults() }) {
                        Text("Reset to defaults")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "Enter your real numbers so DashBuddy can show your true net pay. " +
                    "Defaults are sensible estimates for your vehicle class but will be off — " +
                    "tap any section to set actual values.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))
            VehicleClassPicker(
                selected = eco.vehicleClass,
                onClassChange = viewModel::setVehicleClass,
            )

            Spacer(modifier = Modifier.height(12.dp))
            EconomyEditor(
                economy = eco,
                onTiresChange = viewModel::setTires,
                onOilChange = viewModel::setOil,
                onBrakesChange = viewModel::setBrakes,
                onFluidsChange = viewModel::setFluids,
                onMiscChange = viewModel::setMiscRepairs,
                onDepreciationChange = viewModel::setDepreciation,
                onInsuranceChange = viewModel::setInsurance,
                onRegistrationChange = viewModel::setRegistration,
                onPhoneChange = viewModel::setPhone,
                onExpectedAnnualMilesChange = viewModel::setExpectedAnnualMiles,
            )

            Spacer(modifier = Modifier.height(16.dp))
            TrueCostFooter(operatingCostPerMile = eco.operatingCostPerMile)

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
