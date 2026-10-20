package cloud.trotter.dashbuddy.ui.components.economy

import kotlinx.coroutines.Deferred
import cloud.trotter.dashbuddy.ui.main.settings.garage.WriteResult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType

/** Vehicle costs, shared by the live details host and the staged wizard. */
@Composable
fun EconomyEditor(
    economy: UserEconomy,
    onTireCostChange: NumberSave = { stagedSave {} },
    onTireLifetimeChange: NumberSave = { stagedSave {} },
    onOilCostChange: NumberSave = { stagedSave {} },
    onOilIntervalChange: NumberSave = { stagedSave {} },
    onBrakesCostChange: NumberSave = { stagedSave {} },
    onBrakesIntervalChange: NumberSave = { stagedSave {} },
    onFluidsCostChange: NumberSave = { stagedSave {} },
    onFluidsIntervalChange: NumberSave = { stagedSave {} },
    onMiscYearlyChange: NumberSave = { stagedSave {} },
    onMiscMilesChange: NumberSave = { stagedSave {} },
    onDepreciationIncludeChange: (Boolean) -> Unit = {},
    onPurchasePriceChange: NumberSave = { stagedSave {} },
    onLifetimeMilesChange: NumberSave = { stagedSave {} },
    onInsuranceChange: NumberSave = { stagedSave {} },
    onRegistrationChange: NumberSave = { stagedSave {} },
    onPhoneTotalChange: NumberSave = { stagedSave {} },
    onPhoneLinesChange: (Int) -> Deferred<WriteResult> = { stagedSave {} },
    onPhoneShareChange: (Double) -> Unit = {},
    onExpectedAnnualMilesChange: NumberSave = { stagedSave {} },
    onChainCostChange: NumberSave = { stagedSave {} },
    onChainLifetimeChange: NumberSave = { stagedSave {} },
    onBatteryCostChange: NumberSave = { stagedSave {} },
    onBatteryLifetimeChange: NumberSave = { stagedSave {} },
    onBeltCostChange: NumberSave = { stagedSave {} },
    onBeltLifetimeChange: NumberSave = { stagedSave {} },
    onConsumptionChange: NumberSave = { stagedSave {} },
    onElectricityPriceChange: NumberSave = { stagedSave {} },
    showShared: Boolean = true,
    enabled: Boolean = true,
) {
    val userSet = economy.userSetFields
    val fields = EconomyFieldGroups.fieldsFor(economy.vehicleProfile)
    if (EconomyField.KWH_PER_100_MI in fields || EconomyField.WH_PER_MILE in fields) {
        DecimalInput(
            label = stringResource(if (EconomyField.WH_PER_MILE in fields) R.string.vehicle_wh_per_mile else R.string.vehicle_kwh_per_100_mi),
            value = if (EconomyField.WH_PER_MILE in fields) economy.whPerMile else economy.kwhPer100Mi,
            onValueChange = onConsumptionChange, enabled = enabled, fieldKey = "CONSUMPTION",
        )
        if (showShared) DecimalInput(label = stringResource(R.string.vehicle_electricity_price), value = economy.electricityPricePerKwh,
            onValueChange = onElectricityPriceChange, enabled = enabled, fieldKey = "ELECTRICITY_PRICE")
        else {
            Row(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.vehicle_facts_electricity_price), Modifier.weight(1f))
                Text(Formats.money(economy.electricityPricePerKwh) + "/kWh")
            }
            Text(stringResource(R.string.vehicle_details_electricity_hint), style = MaterialTheme.typography.bodySmall)
        }
    }


    // -------- Tires --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_tires_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.tireCostPerMile)),
        isUserSet = EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_tires_cost_label),
            costValue = economy.tireSetCost,
            onCostChange = onTireCostChange, onIntervalChange = onTireLifetimeChange,
            costKey = "TIRE_COST", intervalKey = "TIRE_LIFETIME", enabled = enabled,
            intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
            intervalValue = economy.tireLifetimeMi
        )
    }

    if (EconomyField.OIL_COST in fields) {
    // -------- Oil changes --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_oil_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.oilCostPerMile)),
        isUserSet = EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_each_label),
            costValue = economy.oilCost,
            onCostChange = onOilCostChange, onIntervalChange = onOilIntervalChange,
            costKey = "OIL_COST", intervalKey = "OIL_INTERVAL", enabled = enabled,
            intervalLabel = stringResource(R.string.economy_editor_every_label),
            intervalValue = economy.oilIntervalMi
        )
    }

    }

    // -------- Brakes --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_brakes_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.brakesCostPerMile)),
        isUserSet = EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_each_label),
            costValue = economy.brakesCost,
            onCostChange = onBrakesCostChange, onIntervalChange = onBrakesIntervalChange,
            costKey = "BRAKES_COST", intervalKey = "BRAKES_INTERVAL", enabled = enabled,
            intervalLabel = stringResource(R.string.economy_editor_every_label),
            intervalValue = economy.brakesIntervalMi
        )
    }

    if (EconomyField.FLUIDS_COST in fields) {
    // -------- Fluids --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_fluids_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.fluidsCostPerMile)),
        isUserSet = EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_each_label),
            costValue = economy.fluidsCost,
            onCostChange = onFluidsCostChange, onIntervalChange = onFluidsIntervalChange,
            costKey = "FLUIDS_COST", intervalKey = "FLUIDS_INTERVAL", enabled = enabled,
            intervalLabel = stringResource(R.string.economy_editor_every_label),
            intervalValue = economy.fluidsIntervalMi
        )
    }

    }

    if (EconomyField.MISC_YEARLY in fields) {
    // -------- Misc repairs --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_misc_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.miscCostPerMile)),
        isUserSet = EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_misc_cost_label),
            costValue = economy.miscYearly,
            onCostChange = onMiscYearlyChange, onIntervalChange = onMiscMilesChange,
            costKey = "MISC_YEARLY", intervalKey = "MISC_YEARLY_MI", enabled = enabled,
            intervalLabel = stringResource(R.string.economy_editor_misc_interval_label),
            intervalValue = economy.miscYearlyMi,
            intervalSuffix = stringResource(R.string.economy_editor_misc_interval_suffix),
            helperText = stringResource(R.string.economy_editor_misc_helper),
        )
    }

    }

    if (EconomyField.CHAIN_COST in fields) {
        EconomyAccordionRow(enabled = enabled, title = stringResource(R.string.vehicle_chain),
            summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.chainCostPerMile)),
            isUserSet = EconomyField.CHAIN_COST in userSet || EconomyField.CHAIN_LIFETIME in userSet) {
            PairedCurrencyAndIntervalInput(costLabel = stringResource(R.string.economy_editor_each_label),
                costValue = economy.chainCost, onCostChange = onChainCostChange, onIntervalChange = onChainLifetimeChange,
                costKey = "CHAIN_COST", intervalKey = "CHAIN_LIFETIME", enabled = enabled,
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.chainLifeMi)
        }
    }

    if (EconomyField.BATTERY_COST in fields) {
        EconomyAccordionRow(enabled = enabled, title = stringResource(R.string.vehicle_battery),
            summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.batteryCostPerMile)),
            isUserSet = EconomyField.BATTERY_COST in userSet || EconomyField.BATTERY_LIFETIME in userSet) {
            PairedCurrencyAndIntervalInput(costLabel = stringResource(R.string.economy_editor_each_label),
                costValue = economy.batteryCost, onCostChange = onBatteryCostChange, onIntervalChange = onBatteryLifetimeChange,
                costKey = "BATTERY_COST", intervalKey = "BATTERY_LIFETIME", enabled = enabled,
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.batteryLifeMi)
        }
    }

    if (EconomyField.BELT_COST in fields) {
        EconomyAccordionRow(enabled = enabled, title = stringResource(R.string.vehicle_belt),
            summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.beltCostPerMile)),
            isUserSet = EconomyField.BELT_COST in userSet || EconomyField.BELT_LIFETIME in userSet) {
            PairedCurrencyAndIntervalInput(costLabel = stringResource(R.string.economy_editor_each_label),
                costValue = economy.beltCost, onCostChange = onBeltCostChange, onIntervalChange = onBeltLifetimeChange,
                costKey = "BELT_COST", intervalKey = "BELT_LIFETIME", enabled = enabled,
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.beltLifeMi)
        }
    }

    // -------- Depreciation --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_depreciation_title),
        summary = if (economy.includeDepreciation) {
            stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.depreciationCostPerMile))
        } else {
            stringResource(R.string.economy_editor_depreciation_off)
        },
        isUserSet = EconomyField.INCLUDE_DEPRECIATION in userSet ||
            EconomyField.PURCHASE_PRICE in userSet ||
            EconomyField.TOTAL_LIFETIME_MI in userSet,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.economy_editor_depreciation_include_label), modifier = Modifier.weight(1f))
            Switch(enabled = enabled,
                checked = economy.includeDepreciation,
                onCheckedChange = onDepreciationIncludeChange,
            )
        }
        if (economy.includeDepreciation) {
            PairedCurrencyAndIntervalInput(
                costLabel = stringResource(R.string.economy_editor_purchase_price_label),
                costValue = economy.purchasePrice,
                onCostChange = onPurchasePriceChange, onIntervalChange = onLifetimeMilesChange,
                costKey = "PURCHASE_PRICE", intervalKey = "TOTAL_LIFETIME_MI", enabled = enabled,
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.totalLifetimeMi,
                helperText = stringResource(R.string.economy_editor_depreciation_helper),
            )
        }
    }

    // -------- Insurance --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_insurance_title),
        summary = stringResource(R.string.economy_editor_per_mile_asterisk_format, Formats.money3(economy.insuranceCostPerMile)),
        isUserSet = EconomyField.INSURANCE_DELTA in userSet,
    ) {
        CurrencyInput(enabled = enabled,
            label = stringResource(R.string.economy_editor_insurance_label),
            value = economy.insuranceDeltaPerMonth,
            onValueChange = onInsuranceChange, fieldKey = "INSURANCE_DELTA",
            suffix = stringResource(R.string.economy_editor_per_month_suffix),
        )
        Text(
            text = stringResource(R.string.economy_editor_insurance_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // -------- Registration --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_registration_title),
        summary = stringResource(R.string.economy_editor_per_mile_asterisk_format, Formats.money3(economy.registrationCostPerMile)),
        isUserSet = EconomyField.REGISTRATION_DELTA in userSet,
    ) {
        CurrencyInput(enabled = enabled,
            label = stringResource(R.string.economy_editor_registration_label),
            value = economy.registrationDeltaPerYear,
            onValueChange = onRegistrationChange, fieldKey = "REGISTRATION_DELTA",
            suffix = stringResource(R.string.economy_editor_per_year_suffix),
        )
        Text(
            text = stringResource(R.string.economy_editor_registration_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (showShared) SharedPhoneInputs(economy, onPhoneTotalChange, onPhoneLinesChange, onPhoneShareChange, enabled)

    // -------- Expected annual gig miles --------
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_annual_miles_title),
        summary = stringResource(R.string.economy_editor_annual_miles_summary_format, Formats.commaInt(economy.expectedAnnualMiles.toInt())),
        isUserSet = EconomyField.EXPECTED_ANNUAL_MI in userSet,
    ) {
        Text(
            text = stringResource(R.string.economy_editor_annual_miles_value_format, Formats.commaInt(economy.expectedAnnualMiles.toInt())),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        DecimalInput(label = stringResource(R.string.economy_editor_annual_miles_title),
            value = economy.expectedAnnualMiles, onValueChange = onExpectedAnnualMilesChange,
            enabled = enabled, fieldKey = "EXPECTED_ANNUAL_MI")
        Text(
            text = stringResource(R.string.economy_editor_annual_miles_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The vehicle-class chip picker both economy surfaces render above the editor. */
@Composable
fun VehicleClassPicker(
    selected: VehicleClass,
    onClassChange: (VehicleClass) -> Unit,
) {
    Text(
        text = stringResource(R.string.economy_editor_vehicle_class_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VehicleClass.entries.filter { it.gasClass != null }.forEach { vc ->
            FilterChip(
                selected = selected == vc,
                onClick = { onClassChange(vc) },
                label = { Text(vc.displayName) },
            )
        }
    }
}

/** Shared settings use the same independent phone fields as the staged wizard. */
@Composable
fun SharedPhoneInputs(
    economy: UserEconomy,
    onPhoneTotalChange: NumberSave,
    onPhoneLinesChange: (Int) -> Deferred<WriteResult>,
    onPhoneShareChange: (Double) -> Unit,
    enabled: Boolean = true,
) {
    val userSet = economy.userSetFields
    EconomyAccordionRow(enabled = enabled,
        title = stringResource(R.string.economy_editor_phone_title),
        summary = stringResource(R.string.economy_editor_per_mile_asterisk_format, Formats.money3(economy.phoneCostPerMile)),
        isUserSet = EconomyField.PHONE_PLAN_TOTAL in userSet ||
            EconomyField.PHONE_PLAN_LINES in userSet ||
            EconomyField.PHONE_BUSINESS_PERCENT in userSet,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyInput(enabled = enabled,
                label = stringResource(R.string.economy_editor_phone_plan_total_label),
                value = economy.phonePlanTotal,
                onValueChange = onPhoneTotalChange, fieldKey = "PHONE_PLAN_TOTAL",
                suffix = stringResource(R.string.economy_editor_per_month_suffix),
                modifier = Modifier.weight(1f),
            )
            IntegerInput(enabled = enabled,
                label = stringResource(R.string.economy_editor_phone_lines_label),
                value = economy.phonePlanLines,
                onValueChange = onPhoneLinesChange, fieldKey = "PHONE_PLAN_LINES",
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.economy_editor_phone_business_percent_format, economy.phoneBusinessPercent.toInt()))
        Slider(enabled = enabled,
            value = economy.phoneBusinessPercent.toFloat(),
            onValueChange = {
                onPhoneShareChange(it.toDouble())
            },
            valueRange = 0f..100f,
        )
        val perLine = economy.phonePlanTotal / economy.phonePlanLines.coerceAtLeast(1)
        Text(
            text = stringResource(
                R.string.economy_editor_phone_per_line_format,
                Formats.money(perLine),
                economy.phoneBusinessPercent.toInt(),
                Formats.money(perLine * economy.phoneBusinessPercent / 100.0),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

}

/** Vehicle-specific inputs shared by settings and other economy surfaces. */
@Composable
fun VehicleMpgAndFuelInputs(
    mpg: Double,
    fuelType: FuelType,
    onMpgChange: NumberSave,
    onFuelTypeChange: (FuelType) -> Unit,
    enabled: Boolean = true,
) {
    CurrencyInput(stringResource(R.string.economy_editor_mpg_label), mpg, onMpgChange,
        Modifier.fillMaxWidth(), showCurrencySymbol = false, enabled = enabled, fieldKey = "VEHICLE_MPG",
        valid = { nonnegativeNumber(it)?.takeIf { value -> value > 0 } })
    Text(
        text = stringResource(R.string.economy_editor_fuel_type_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FuelType.entries.filter { it != FuelType.ELECTRICITY }.forEach { type ->
            FilterChip(
                enabled = enabled,
                selected = fuelType == type,
                onClick = { onFuelTypeChange(type) },
                label = { Text(type.displayName) },
            )
        }
    }
}

/** The live "your true cost vs IRS rate" footer both economy surfaces render below the editor. */
@Composable
fun TrueCostFooter(operatingCostPerMile: Double) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.economy_editor_true_cost_format, Formats.money(operatingCostPerMile)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}


@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun EconomyEditorPreview() = DashBuddyTheme {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val session = remember { EditSession(scope) }
    androidx.compose.runtime.CompositionLocalProvider(LocalEditSession provides session) {
        Column(Modifier.padding(16.dp)) {
            EconomyEditor(UserEconomy())
        }
    }
}
