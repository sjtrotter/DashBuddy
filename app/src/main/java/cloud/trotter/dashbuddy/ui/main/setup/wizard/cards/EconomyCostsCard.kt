package cloud.trotter.dashbuddy.ui.main.setup.wizard.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.ui.components.economy.CurrencyInput
import cloud.trotter.dashbuddy.ui.components.economy.EconomyAccordionRow
import cloud.trotter.dashbuddy.ui.components.economy.IntegerInput
import cloud.trotter.dashbuddy.ui.components.economy.PairedCurrencyAndIntervalInput
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardState
import cloud.trotter.dashbuddy.ui.main.setup.wizard.model.WizardStep
import cloud.trotter.dashbuddy.ui.main.setup.wizard.components.WizardCardHeader

/**
 * The ECONOMY_COSTS wizard step. A single scrolling card with:
 * 1. Vehicle class chip picker (drives cost defaults)
 * 2. Accordion of 10 cost sections (collapsed by default; expand to edit)
 * 3. Expected annual dash miles slider (amortizes fixed costs)
 * 4. Live "Your true cost: $X.XX/mi" footer comparing to the IRS standard rate
 *
 * Defaults flow from the selected [VehicleClass] for any field not yet user-set;
 * editing a field marks it as user-set so subsequent class changes don't
 * overwrite it.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onExpectedAnnualDashMilesChange: (Double) -> Unit,
    onTimeConstantsChange: (Double, Double) -> Unit,
) {
    // Build a live UserEconomy from current state so we can show $/mi previews.
    val liveEconomy = remember(state) { state.toUserEconomy() }
    val userSet = state.userSetEconomyFields

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            WizardCardHeader(step = WizardStep.ECONOMY_COSTS)

            // Vehicle class chip picker
            Text(
                text = "Vehicle class",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                VehicleClass.entries.forEach { vc ->
                    FilterChip(
                        selected = state.vehicleClass == vc,
                        onClick = { onClassChange(vc) },
                        label = { Text(vc.displayName) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // -------- Tires --------
            EconomyAccordionRow(
                title = "Tires",
                summary = "%.3f/mi".format(liveEconomy.tireCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet,
            ) {
                PairedCurrencyAndIntervalInput(
                    costLabel = "Set of 4",
                    costValue = state.tireSetCost,
                    onCostChange = { onTiresChange(it, state.tireLifetimeMi) },
                    intervalLabel = "Lifetime",
                    intervalValue = state.tireLifetimeMi,
                    onIntervalChange = { onTiresChange(state.tireSetCost, it) },
                )
            }

            // -------- Oil changes --------
            EconomyAccordionRow(
                title = "Oil changes",
                summary = "%.3f/mi".format(liveEconomy.oilCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet,
            ) {
                PairedCurrencyAndIntervalInput(
                    costLabel = "Each",
                    costValue = state.oilCost,
                    onCostChange = { onOilChange(it, state.oilIntervalMi) },
                    intervalLabel = "Every",
                    intervalValue = state.oilIntervalMi,
                    onIntervalChange = { onOilChange(state.oilCost, it) },
                )
            }

            // -------- Brakes --------
            EconomyAccordionRow(
                title = "Brakes",
                summary = "%.3f/mi".format(liveEconomy.brakesCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet,
            ) {
                PairedCurrencyAndIntervalInput(
                    costLabel = "Each",
                    costValue = state.brakesCost,
                    onCostChange = { onBrakesChange(it, state.brakesIntervalMi) },
                    intervalLabel = "Every",
                    intervalValue = state.brakesIntervalMi,
                    onIntervalChange = { onBrakesChange(state.brakesCost, it) },
                )
            }

            // -------- Fluids --------
            EconomyAccordionRow(
                title = "Fluids",
                summary = "%.3f/mi".format(liveEconomy.fluidsCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet,
            ) {
                PairedCurrencyAndIntervalInput(
                    costLabel = "Each",
                    costValue = state.fluidsCost,
                    onCostChange = { onFluidsChange(it, state.fluidsIntervalMi) },
                    intervalLabel = "Every",
                    intervalValue = state.fluidsIntervalMi,
                    onIntervalChange = { onFluidsChange(state.fluidsCost, it) },
                )
            }

            // -------- Misc repairs --------
            EconomyAccordionRow(
                title = "Misc repairs",
                summary = "%.3f/mi".format(liveEconomy.miscCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet,
            ) {
                PairedCurrencyAndIntervalInput(
                    costLabel = "Yearly budget",
                    costValue = state.miscYearly,
                    onCostChange = { onMiscChange(it, state.miscYearlyMi) },
                    intervalLabel = "Driving",
                    intervalValue = state.miscYearlyMi,
                    onIntervalChange = { onMiscChange(state.miscYearly, it) },
                    intervalSuffix = "mi/yr",
                    helperText = "Annual catch-all for repairs not in oil/brakes/fluids.",
                )
            }

            // -------- Depreciation --------
            EconomyAccordionRow(
                title = "Depreciation",
                summary = if (state.includeDepreciation)
                    "%.3f/mi".format(liveEconomy.depreciationCostPerMile).let { "\$$it" }
                else "off",
                isUserSet = EconomyField.INCLUDE_DEPRECIATION in userSet ||
                    EconomyField.PURCHASE_PRICE in userSet ||
                    EconomyField.TOTAL_LIFETIME_MI in userSet,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include depreciation", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.includeDepreciation,
                        onCheckedChange = {
                            onDepreciationChange(it, state.purchasePrice, state.totalLifetimeMi)
                        },
                    )
                }
                if (state.includeDepreciation) {
                    PairedCurrencyAndIntervalInput(
                        costLabel = "Purchase price",
                        costValue = state.purchasePrice,
                        onCostChange = {
                            onDepreciationChange(true, it, state.totalLifetimeMi)
                        },
                        intervalLabel = "Lifetime",
                        intervalValue = state.totalLifetimeMi,
                        onIntervalChange = {
                            onDepreciationChange(true, state.purchasePrice, it)
                        },
                        helperText = "Total expected miles from new to retirement, " +
                            "e.g. 200,000 for a typical sedan.",
                    )
                }
            }

            // -------- Insurance --------
            EconomyAccordionRow(
                title = "Insurance",
                summary = "%.3f/mi*".format(liveEconomy.insuranceCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.INSURANCE_DELTA in userSet,
            ) {
                CurrencyInput(
                    label = "Extra for dashing",
                    value = state.insuranceDeltaPerMonth,
                    onValueChange = onInsuranceChange,
                    suffix = "/mo",
                )
                Text(
                    text = "Monthly add-on for rideshare/delivery rider above your personal policy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -------- Registration --------
            EconomyAccordionRow(
                title = "Registration",
                summary = "%.3f/mi*".format(liveEconomy.registrationCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.REGISTRATION_DELTA in userSet,
            ) {
                CurrencyInput(
                    label = "Commercial delta",
                    value = state.registrationDeltaPerYear,
                    onValueChange = onRegistrationChange,
                    suffix = "/yr",
                )
                Text(
                    text = "Annual fee above your personal registration (commercial " +
                        "registration / inspection / endorsement).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -------- Phone & data --------
            EconomyAccordionRow(
                title = "Phone & data",
                summary = "%.3f/mi*".format(liveEconomy.phoneCostPerMile).let { "\$$it" },
                isUserSet = EconomyField.PHONE_PLAN_TOTAL in userSet ||
                    EconomyField.PHONE_PLAN_LINES in userSet ||
                    EconomyField.PHONE_DASH_PERCENT in userSet,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyInput(
                        label = "Plan total",
                        value = state.phonePlanTotal,
                        onValueChange = {
                            onPhoneChange(it, state.phonePlanLines, state.phoneDashPercent)
                        },
                        suffix = "/mo",
                        modifier = Modifier.weight(1f),
                    )
                    IntegerInput(
                        label = "Lines",
                        value = state.phonePlanLines,
                        onValueChange = {
                            onPhoneChange(state.phonePlanTotal, it, state.phoneDashPercent)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text("% for dashing: ${state.phoneDashPercent.toInt()}%")
                Slider(
                    value = state.phoneDashPercent.toFloat(),
                    onValueChange = {
                        onPhoneChange(state.phonePlanTotal, state.phonePlanLines, it.toDouble())
                    },
                    valueRange = 0f..100f,
                )
                Text(
                    text = "Your line: \$${"%.2f".format(state.phonePlanTotal / state.phonePlanLines.coerceAtLeast(1))}/mo" +
                        " × ${state.phoneDashPercent.toInt()}%" +
                        " = \$${"%.2f".format((state.phonePlanTotal / state.phonePlanLines.coerceAtLeast(1)) * state.phoneDashPercent / 100.0)}/mo dashing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -------- Time estimates --------
            EconomyAccordionRow(
                title = "Time estimates",
                summary = "${"%.1f".format(state.avgMinutesPerMile)} min/mi",
                isUserSet = EconomyField.AVG_MIN_PER_MILE in userSet ||
                    EconomyField.BASE_PICKUP_MIN in userSet,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CurrencyInput(
                        label = "Min per mile",
                        value = state.avgMinutesPerMile,
                        onValueChange = { onTimeConstantsChange(it, state.basePickupMinutes) },
                        modifier = Modifier.weight(1f),
                    )
                    CurrencyInput(
                        label = "Base pickup min",
                        value = state.basePickupMinutes,
                        onValueChange = { onTimeConstantsChange(state.avgMinutesPerMile, it) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "Per-mile pace and base pickup overhead used to estimate offer time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // -------- Expected annual dash miles --------
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Expected dash miles: ${state.expectedAnnualDashMiles.toInt().formatWithCommas()} mi/yr",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Used to amortize fixed costs (insurance, registration, phone) into a per-mile rate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = state.expectedAnnualDashMiles.toFloat(),
                onValueChange = { onExpectedAnnualDashMilesChange(it.toDouble()) },
                valueRange = 500f..30_000f,
                steps = 58, // 500-mile increments
            )

            // -------- Footer: live total cost vs IRS standard --------
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
                        text = "Your true cost: \$${"%.2f".format(liveEconomy.operatingCostPerMile)}/mi",
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
        }
    }
}

private fun Int.formatWithCommas(): String =
    "%,d".format(this)

/**
 * Builds a [UserEconomy] from the wizard state so the card can display live per-mile
 * derived values without going through the repository.
 */
private fun WizardState.toUserEconomy() = UserEconomy(
    vehicleClass = vehicleClass,
    vehicleMpg = estimatedMpg.toDouble(),
    gasPricePerGallon = gasPrice.toDouble(),
    avgMinutesPerMile = avgMinutesPerMile,
    basePickupMinutes = basePickupMinutes,
    tireSetCost = tireSetCost, tireLifetimeMi = tireLifetimeMi,
    oilCost = oilCost, oilIntervalMi = oilIntervalMi,
    brakesCost = brakesCost, brakesIntervalMi = brakesIntervalMi,
    fluidsCost = fluidsCost, fluidsIntervalMi = fluidsIntervalMi,
    miscYearly = miscYearly, miscYearlyMi = miscYearlyMi,
    includeDepreciation = includeDepreciation,
    purchasePrice = purchasePrice, totalLifetimeMi = totalLifetimeMi,
    insuranceDeltaPerMonth = insuranceDeltaPerMonth,
    registrationDeltaPerYear = registrationDeltaPerYear,
    expectedAnnualDashMiles = expectedAnnualDashMiles,
    phonePlanTotal = phonePlanTotal,
    phonePlanLines = phonePlanLines,
    phoneDashPercent = phoneDashPercent,
    userSetFields = userSetEconomyFields,
)
