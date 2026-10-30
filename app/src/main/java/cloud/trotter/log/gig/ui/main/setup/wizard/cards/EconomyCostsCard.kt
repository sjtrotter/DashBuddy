package cloud.trotter.log.gig.ui.main.setup.wizard.cards

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
import cloud.trotter.log.gig.ui.components.economy.EditSession
import cloud.trotter.log.gig.ui.components.economy.LocalEditSession
import cloud.trotter.log.gig.ui.components.economy.stagedSave
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.domain.model.vehicle.VehicleClass
import cloud.trotter.log.gig.domain.evaluation.EconomyField
import cloud.trotter.log.gig.domain.evaluation.EconomyFieldGroups
import cloud.trotter.log.gig.ui.components.economy.EconomyEditor
import cloud.trotter.log.gig.ui.components.economy.TrueCostFooter
import cloud.trotter.log.gig.ui.components.economy.VehicleClassPicker
import cloud.trotter.log.gig.feature.setup.wizard.components.WizardCardHeader
import cloud.trotter.log.gig.feature.setup.wizard.vehicle.VehicleDraftState
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardState
import cloud.trotter.log.gig.feature.setup.wizard.model.WizardStep
import cloud.trotter.log.gig.feature.setup.wizard.model.toUserEconomy

/** Staged costs: finishing a field changes only WizardState until Finish. */
@Composable
fun EconomyCostsCard(
    state: WizardState,
    vehicle: VehicleDraftState,
    onClassChange: (VehicleClass) -> Unit,
    session: EditSession,
    onChainCostChange: (Double) -> Unit,
    onChainLifetimeChange: (Double) -> Unit,
    onBatteryCostChange: (Double) -> Unit,
    onBatteryLifetimeChange: (Double) -> Unit,
    onBeltCostChange: (Double) -> Unit,
    onBeltLifetimeChange: (Double) -> Unit,
    onTireCostChange: (Double) -> Unit,
    onTireLifetimeChange: (Double) -> Unit,
    onOilCostChange: (Double) -> Unit,
    onOilIntervalChange: (Double) -> Unit,
    onBrakesCostChange: (Double) -> Unit,
    onBrakesIntervalChange: (Double) -> Unit,
    onFluidsCostChange: (Double) -> Unit,
    onFluidsIntervalChange: (Double) -> Unit,
    onMiscYearlyChange: (Double) -> Unit,
    onMiscMilesChange: (Double) -> Unit,
    onPurchasePriceChange: (Double) -> Unit,
    onLifetimeMilesChange: (Double) -> Unit,
    onInsuranceChange: (Double) -> Unit,
    onRegistrationChange: (Double) -> Unit,
    onExpectedAnnualMilesChange: (Double) -> Unit,
    onPhoneTotalChange: (Double) -> Unit,
    onPhoneLinesChange: (Int) -> Unit,
    onPhoneShareChange: (Double) -> Unit,
    onDepreciationIncludeChange: (Boolean) -> Unit,
    onConsumptionChange: (Double) -> Unit,
    onElectricityPriceChange: (Double) -> Unit,
) {
    // Build a live UserEconomy from current state so the editor can show
    // $/mi summaries without going through the repository.
    val liveEconomy = remember(state, vehicle) { state.toUserEconomy(vehicle) }

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
            if (EconomyField.VEHICLE_CLASS in EconomyFieldGroups.fieldsFor(vehicle.profile)) VehicleClassPicker(
                selected = vehicle.vehicleClass,
                onClassChange = onClassChange,
            )

            Spacer(modifier = Modifier.height(12.dp))
            CompositionLocalProvider(
                LocalEditSession provides session,
            ) {
                EconomyEditor(economy = liveEconomy,
                    onChainCostChange = { value -> stagedSave { onChainCostChange(value) } },
                    onChainLifetimeChange = { value -> stagedSave { onChainLifetimeChange(value) } },
                    onBatteryCostChange = { value -> stagedSave { onBatteryCostChange(value) } },
                    onBatteryLifetimeChange = { value -> stagedSave { onBatteryLifetimeChange(value) } },
                    onBeltCostChange = { value -> stagedSave { onBeltCostChange(value) } },
                    onBeltLifetimeChange = { value -> stagedSave { onBeltLifetimeChange(value) } },
                    onTireCostChange = { value -> stagedSave { onTireCostChange(value) } },
                    onTireLifetimeChange = { value -> stagedSave { onTireLifetimeChange(value) } },
                    onOilCostChange = { value -> stagedSave { onOilCostChange(value) } },
                    onOilIntervalChange = { value -> stagedSave { onOilIntervalChange(value) } },
                    onBrakesCostChange = { value -> stagedSave { onBrakesCostChange(value) } },
                    onBrakesIntervalChange = { value -> stagedSave { onBrakesIntervalChange(value) } },
                    onFluidsCostChange = { value -> stagedSave { onFluidsCostChange(value) } },
                    onFluidsIntervalChange = { value -> stagedSave { onFluidsIntervalChange(value) } },
                    onMiscYearlyChange = { value -> stagedSave { onMiscYearlyChange(value) } },
                    onMiscMilesChange = { value -> stagedSave { onMiscMilesChange(value) } },
                    onPurchasePriceChange = { value -> stagedSave { onPurchasePriceChange(value) } },
                    onLifetimeMilesChange = { value -> stagedSave { onLifetimeMilesChange(value) } },
                    onInsuranceChange = { value -> stagedSave { onInsuranceChange(value) } },
                    onRegistrationChange = { value -> stagedSave { onRegistrationChange(value) } },
                    onExpectedAnnualMilesChange = { value -> stagedSave { onExpectedAnnualMilesChange(value) } },
                    onPhoneTotalChange = { value -> stagedSave { onPhoneTotalChange(value) } },
                    onPhoneLinesChange = { value -> stagedSave { onPhoneLinesChange(value) } },
                    onPhoneShareChange = onPhoneShareChange,
                    onConsumptionChange = { value -> stagedSave { onConsumptionChange(value) } },
                    onElectricityPriceChange = { value -> stagedSave { onElectricityPriceChange(value) } },
                    onDepreciationIncludeChange = onDepreciationIncludeChange,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            TrueCostFooter(operatingCostPerMile = liveEconomy.operatingCostPerMile)
        }
    }
}
