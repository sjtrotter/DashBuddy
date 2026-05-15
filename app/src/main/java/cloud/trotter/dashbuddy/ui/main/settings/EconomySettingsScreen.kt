package cloud.trotter.dashbuddy.ui.main.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.ui.components.economy.CurrencyInput
import cloud.trotter.dashbuddy.ui.components.economy.EconomyAccordionRow
import cloud.trotter.dashbuddy.ui.components.economy.IntegerInput
import cloud.trotter.dashbuddy.ui.components.economy.PairedCurrencyAndIntervalInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EconomySettingsScreen(
    onBack: () -> Unit,
    viewModel: EconomySettingsViewModel = hiltViewModel(),
) {
    val eco by viewModel.userEconomy.collectAsState()

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

            // Vehicle class picker
            Text(
                text = "Vehicle class",
                style = MaterialTheme.typography.titleSmall,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                VehicleClass.entries.forEach { vc ->
                    FilterChip(
                        selected = eco.vehicleClass == vc,
                        onClick = { viewModel.setVehicleClass(vc) },
                        label = { Text(vc.displayName) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            EconomyAccordions(eco = eco, viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Your true cost: \$${"%.2f".format(eco.operatingCostPerMile)}/mi",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "IRS standard mileage rate: \$0.67/mi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EconomyAccordions(eco: UserEconomy, viewModel: EconomySettingsViewModel) {
    val userSet = eco.userSetFields

    EconomyAccordionRow(
        title = "Tires",
        summary = "\$${"%.3f".format(eco.tireCostPerMile)}/mi",
        isUserSet = EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Set of 4",
            costValue = eco.tireSetCost,
            onCostChange = { viewModel.setTires(it, eco.tireLifetimeMi) },
            intervalLabel = "Lifetime",
            intervalValue = eco.tireLifetimeMi,
            onIntervalChange = { viewModel.setTires(eco.tireSetCost, it) },
        )
    }

    EconomyAccordionRow(
        title = "Oil changes",
        summary = "\$${"%.3f".format(eco.oilCostPerMile)}/mi",
        isUserSet = EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Each",
            costValue = eco.oilCost,
            onCostChange = { viewModel.setOil(it, eco.oilIntervalMi) },
            intervalLabel = "Every",
            intervalValue = eco.oilIntervalMi,
            onIntervalChange = { viewModel.setOil(eco.oilCost, it) },
        )
    }

    EconomyAccordionRow(
        title = "Brakes",
        summary = "\$${"%.3f".format(eco.brakesCostPerMile)}/mi",
        isUserSet = EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Each",
            costValue = eco.brakesCost,
            onCostChange = { viewModel.setBrakes(it, eco.brakesIntervalMi) },
            intervalLabel = "Every",
            intervalValue = eco.brakesIntervalMi,
            onIntervalChange = { viewModel.setBrakes(eco.brakesCost, it) },
        )
    }

    EconomyAccordionRow(
        title = "Fluids",
        summary = "\$${"%.3f".format(eco.fluidsCostPerMile)}/mi",
        isUserSet = EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Each",
            costValue = eco.fluidsCost,
            onCostChange = { viewModel.setFluids(it, eco.fluidsIntervalMi) },
            intervalLabel = "Every",
            intervalValue = eco.fluidsIntervalMi,
            onIntervalChange = { viewModel.setFluids(eco.fluidsCost, it) },
        )
    }

    EconomyAccordionRow(
        title = "Misc repairs",
        summary = "\$${"%.3f".format(eco.miscCostPerMile)}/mi",
        isUserSet = EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Yearly budget",
            costValue = eco.miscYearly,
            onCostChange = { viewModel.setMiscRepairs(it, eco.miscYearlyMi) },
            intervalLabel = "Driving",
            intervalValue = eco.miscYearlyMi,
            onIntervalChange = { viewModel.setMiscRepairs(eco.miscYearly, it) },
            intervalSuffix = "mi/yr",
        )
    }

    EconomyAccordionRow(
        title = "Depreciation",
        summary = if (eco.includeDepreciation) "\$${"%.3f".format(eco.depreciationCostPerMile)}/mi" else "off",
        isUserSet = EconomyField.INCLUDE_DEPRECIATION in userSet ||
            EconomyField.PURCHASE_PRICE in userSet ||
            EconomyField.TOTAL_LIFETIME_MI in userSet,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include depreciation", modifier = Modifier.weight(1f))
            Switch(
                checked = eco.includeDepreciation,
                onCheckedChange = {
                    viewModel.setDepreciation(it, eco.purchasePrice, eco.totalLifetimeMi)
                },
            )
        }
        if (eco.includeDepreciation) {
            PairedCurrencyAndIntervalInput(
                costLabel = "Purchase price",
                costValue = eco.purchasePrice,
                onCostChange = { viewModel.setDepreciation(true, it, eco.totalLifetimeMi) },
                intervalLabel = "Lifetime",
                intervalValue = eco.totalLifetimeMi,
                onIntervalChange = { viewModel.setDepreciation(true, eco.purchasePrice, it) },
                helperText = "Total expected miles from new to retirement.",
            )
        }
    }

    EconomyAccordionRow(
        title = "Insurance",
        summary = "\$${"%.3f".format(eco.insuranceCostPerMile)}/mi*",
        isUserSet = EconomyField.INSURANCE_DELTA in userSet,
    ) {
        CurrencyInput(
            label = "Extra for gig work",
            value = eco.insuranceDeltaPerMonth,
            onValueChange = viewModel::setInsurance,
            suffix = "/mo",
        )
    }

    EconomyAccordionRow(
        title = "Registration",
        summary = "\$${"%.3f".format(eco.registrationCostPerMile)}/mi*",
        isUserSet = EconomyField.REGISTRATION_DELTA in userSet,
    ) {
        CurrencyInput(
            label = "Commercial delta",
            value = eco.registrationDeltaPerYear,
            onValueChange = viewModel::setRegistration,
            suffix = "/yr",
        )
    }

    EconomyAccordionRow(
        title = "Phone & data",
        summary = "\$${"%.3f".format(eco.phoneCostPerMile)}/mi*",
        isUserSet = EconomyField.PHONE_PLAN_TOTAL in userSet ||
            EconomyField.PHONE_PLAN_LINES in userSet ||
            EconomyField.PHONE_DASH_PERCENT in userSet,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyInput(
                label = "Plan total",
                value = eco.phonePlanTotal,
                onValueChange = { viewModel.setPhone(it, eco.phonePlanLines, eco.phoneDashPercent) },
                suffix = "/mo",
                modifier = Modifier.weight(1f),
            )
            IntegerInput(
                label = "Lines",
                value = eco.phonePlanLines,
                onValueChange = { viewModel.setPhone(eco.phonePlanTotal, it, eco.phoneDashPercent) },
                modifier = Modifier.weight(1f),
            )
        }
        Text("% for gig work: ${eco.phoneDashPercent.toInt()}%")
        Slider(
            value = eco.phoneDashPercent.toFloat(),
            onValueChange = { viewModel.setPhone(eco.phonePlanTotal, eco.phonePlanLines, it.toDouble()) },
            valueRange = 0f..100f,
        )
    }

    EconomyAccordionRow(
        title = "Expected gig miles / yr",
        summary = "${eco.expectedAnnualDashMiles.toInt()} mi",
        isUserSet = EconomyField.EXPECTED_ANNUAL_DASH_MI in userSet,
    ) {
        Text(
            text = "${eco.expectedAnnualDashMiles.toInt()} miles per year",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = eco.expectedAnnualDashMiles.toFloat(),
            onValueChange = { viewModel.setExpectedAnnualDashMiles(it.toDouble()) },
            valueRange = 500f..30_000f,
        )
        Text(
            text = "Used to amortize fixed costs (insurance, registration, phone) per mile.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
