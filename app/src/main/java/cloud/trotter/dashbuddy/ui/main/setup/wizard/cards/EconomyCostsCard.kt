package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.ui.components.economy.EconomyEditor
import cloud.trotter.dashbuddy.ui.components.economy.TrueCostFooter
import cloud.trotter.dashbuddy.ui.components.economy.VehicleClassPicker
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardState
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.toUserEconomy

/**
 * The ECONOMY_COSTS wizard step. Chrome only: the card, header, scroll, and
 * the live-economy bridge from [WizardState]. The ten cost sections, vehicle
 * class picker, and true-cost footer are the shared [EconomyEditor] family
 * (#357) — also used by the Personal Economy settings screen.
 *
 * Defaults flow from the selected [VehicleClass] for any field not yet user-set;
 * editing a field marks it as user-set so subsequent class changes don't
 * overwrite it.
 */
@Composable
fun EconomyCostsCard(
    state: WizardState,
    onClassChange: (VehicleClass) -> Unit,
    onTiresChange: (Double, Double) -> Unit,
    onOilChange: (Double, Double) -> Unit,
    onBrakesChange: (Double, Double) -> Unit,
    onFluidsChange: (Double, Double) -> Unit,
    onMiscChange: (Double, Double) -> Unit,
    onDepreciationChange: (Boolean, Double, Double) -> Unit,
    onInsuranceChange: (Double) -> Unit,
    onRegistrationChange: (Double) -> Unit,
    onPhoneChange: (Double, Int, Double) -> Unit,
    onExpectedAnnualMilesChange: (Double) -> Unit,
    onTimeConstantsChange: (Double, Double) -> Unit,
) {
    // Build a live UserEconomy from current state so the editor can show
    // $/mi summaries without going through the repository.
    val liveEconomy = remember(state) { state.toUserEconomy() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            WizardCardHeader(step = WizardStep.ECONOMY_COSTS)

            Spacer(modifier = Modifier.height(12.dp))
            VehicleClassPicker(
                selected = state.vehicleClass,
                onClassChange = onClassChange,
            )

            Spacer(modifier = Modifier.height(12.dp))
            EconomyEditor(
                economy = liveEconomy,
                onTiresChange = onTiresChange,
                onOilChange = onOilChange,
                onBrakesChange = onBrakesChange,
                onFluidsChange = onFluidsChange,
                onMiscChange = onMiscChange,
                onDepreciationChange = onDepreciationChange,
                onInsuranceChange = onInsuranceChange,
                onRegistrationChange = onRegistrationChange,
                onPhoneChange = onPhoneChange,
                onExpectedAnnualMilesChange = onExpectedAnnualMilesChange,
            )

            Spacer(modifier = Modifier.height(16.dp))
            TrueCostFooter(operatingCostPerMile = liveEconomy.operatingCostPerMile)
        }
    }
}
