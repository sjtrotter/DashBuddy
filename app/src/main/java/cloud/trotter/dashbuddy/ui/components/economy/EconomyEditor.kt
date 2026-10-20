package cloud.trotter.dashbuddy.ui.components.economy

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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * The shared personal-economy editor (#357): the ten cost accordions used by
 * BOTH the setup wizard's ECONOMY_COSTS step and the Personal Economy settings
 * screen. Defined exactly once so the two surfaces can never diverge again.
 *
 * Pure data-in / lambdas-out: values and derived per-mile summaries come from
 * [economy] (the wizard builds a live [UserEconomy] from its draft state; the
 * settings screen passes the persisted one), edits flow up through the
 * per-section callbacks. Chrome — Card vs Scaffold, headers, footers — stays
 * with the caller. [VehicleClassPicker] and [TrueCostFooter] live here too
 * because both surfaces render them identically.
 */
@Composable
fun EconomyEditor(
    economy: UserEconomy,
    onTiresChange: (setCost: Double?, lifetimeMi: Double?) -> Unit,
    onOilChange: (cost: Double?, intervalMi: Double?) -> Unit,
    onBrakesChange: (cost: Double?, intervalMi: Double?) -> Unit,
    onFluidsChange: (cost: Double?, intervalMi: Double?) -> Unit,
    onMiscChange: (yearly: Double?, yearlyMi: Double?) -> Unit,
    onDepreciationIncludeChange: (Boolean) -> Unit,
    onDepreciationValuesChange: (price: Double?, lifetimeMi: Double?) -> Unit,
    onInsuranceChange: (perMonth: Double) -> Unit,
    onRegistrationChange: (perYear: Double) -> Unit,
    onPhoneChange: (total: Double, lines: Int, dashPercent: Double) -> Unit,
    onExpectedAnnualMilesChange: (miles: Double) -> Unit,
    onChainChange: (Double?, Double?) -> Unit = { _, _ -> },
    onBatteryChange: (Double?, Double?) -> Unit = { _, _ -> },
    onBeltChange: (Double?, Double?) -> Unit = { _, _ -> },
    onConsumptionChange: (Double) -> Unit = {},
    onElectricityPriceChange: (Double) -> Unit = {},
    showShared: Boolean = true,
    enabled: Boolean = true,
) {
    val userSet = economy.userSetFields
    val fields = EconomyFieldGroups.fieldsFor(economy.vehicleProfile)
    if (EconomyField.KWH_PER_100_MI in fields || EconomyField.WH_PER_MILE in fields) {
        DecimalInput(
            label = stringResource(if (EconomyField.WH_PER_MILE in fields) R.string.vehicle_wh_per_mile else R.string.vehicle_kwh_per_100_mi),
            value = if (EconomyField.WH_PER_MILE in fields) economy.whPerMile else economy.kwhPer100Mi,
            onValueChange = onConsumptionChange, enabled = enabled,
        )
        DecimalInput(label = stringResource(R.string.vehicle_electricity_price), value = economy.electricityPricePerKwh,
            onValueChange = onElectricityPriceChange, enabled = enabled,
            supportingText = if (showShared) null else stringResource(R.string.vehicle_details_electricity_hint))
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
            onValueChange = onTiresChange, enabled = enabled,
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
            onValueChange = onOilChange, enabled = enabled,
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
            onValueChange = onBrakesChange, enabled = enabled,
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
            onValueChange = onFluidsChange, enabled = enabled,
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
            onValueChange = onMiscChange, enabled = enabled,
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
                costValue = economy.chainCost, onValueChange = onChainChange, enabled = enabled,
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.chainLifeMi)
        }
    }

    if (EconomyField.BATTERY_COST in fields) {
        EconomyAccordionRow(enabled = enabled, title = stringResource(R.string.vehicle_battery),
            summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.batteryCostPerMile)),
            isUserSet = EconomyField.BATTERY_COST in userSet || EconomyField.BATTERY_LIFETIME in userSet) {
            PairedCurrencyAndIntervalInput(costLabel = stringResource(R.string.economy_editor_each_label),
                costValue = economy.batteryCost, onValueChange = onBatteryChange, enabled = enabled,
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.batteryLifeMi)
        }
    }

    if (EconomyField.BELT_COST in fields) {
        EconomyAccordionRow(enabled = enabled, title = stringResource(R.string.vehicle_belt),
            summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.beltCostPerMile)),
            isUserSet = EconomyField.BELT_COST in userSet || EconomyField.BELT_LIFETIME in userSet) {
            PairedCurrencyAndIntervalInput(costLabel = stringResource(R.string.economy_editor_each_label),
                costValue = economy.beltCost, onValueChange = onBeltChange, enabled = enabled,
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
                onValueChange = onDepreciationValuesChange, enabled = enabled,
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
            onValueChange = onInsuranceChange,
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
            onValueChange = onRegistrationChange,
            suffix = stringResource(R.string.economy_editor_per_year_suffix),
        )
        Text(
            text = stringResource(R.string.economy_editor_registration_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // -------- Phone & data --------
    if (showShared) {
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
                onValueChange = {
                    onPhoneChange(it, economy.phonePlanLines, economy.phoneBusinessPercent)
                },
                suffix = stringResource(R.string.economy_editor_per_month_suffix),
                modifier = Modifier.weight(1f),
            )
            IntegerInput(enabled = enabled,
                label = stringResource(R.string.economy_editor_phone_lines_label),
                value = economy.phonePlanLines,
                onValueChange = {
                    onPhoneChange(economy.phonePlanTotal, it, economy.phoneBusinessPercent)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.economy_editor_phone_business_percent_format, economy.phoneBusinessPercent.toInt()))
        Slider(enabled = enabled,
            value = economy.phoneBusinessPercent.toFloat(),
            onValueChange = {
                onPhoneChange(economy.phonePlanTotal, economy.phonePlanLines, it.toDouble())
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
        Slider(enabled = enabled,
            value = economy.expectedAnnualMiles.toFloat(),
            onValueChange = { onExpectedAnnualMilesChange(it.toDouble()) },
            valueRange = 500f..30_000f,
            steps = 58, // 500-mile increments
        )
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

/** Vehicle-specific inputs shared by settings and other economy surfaces. */
@Composable
fun VehicleMpgAndFuelInputs(
    mpg: Double,
    fuelType: FuelType,
    onMpgChange: (Double) -> Unit,
    onFuelTypeChange: (FuelType) -> Unit,
    enabled: Boolean = true,
) {
    val state = remember { FinishedNumberState(mpg.toString()) { text ->
        nonnegativeNumber(text)?.takeIf { it > 0 }
    } }
    SideEffect { state.sync(mpg.toString()) }
    FinishedNumberField(stringResource(R.string.economy_editor_mpg_label), state,
        { state.commit(onMpgChange) }, Modifier.fillMaxWidth(), enabled = enabled)
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
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(16.dp)) {
            val economy = UserEconomy()
            VehicleClassPicker(selected = economy.vehicleClass, onClassChange = {})
            EconomyEditor(
                economy = economy,
                onTiresChange = { _, _ -> },
                onOilChange = { _, _ -> },
                onBrakesChange = { _, _ -> },
                onFluidsChange = { _, _ -> },
                onMiscChange = { _, _ -> },
                onDepreciationIncludeChange = {},
                onDepreciationValuesChange = { _, _ -> },
                onInsuranceChange = {},
                onRegistrationChange = {},
                onPhoneChange = { _, _, _ -> },
                onExpectedAnnualMilesChange = {},
            )
            TrueCostFooter(operatingCostPerMile = economy.operatingCostPerMile)
        }
    }
}
