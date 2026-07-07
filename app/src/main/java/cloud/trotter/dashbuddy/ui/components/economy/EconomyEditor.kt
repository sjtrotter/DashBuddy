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
import androidx.compose.runtime.Composable
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
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass

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
    onTiresChange: (setCost: Double, lifetimeMi: Double) -> Unit,
    onOilChange: (cost: Double, intervalMi: Double) -> Unit,
    onBrakesChange: (cost: Double, intervalMi: Double) -> Unit,
    onFluidsChange: (cost: Double, intervalMi: Double) -> Unit,
    onMiscChange: (yearly: Double, yearlyMi: Double) -> Unit,
    onDepreciationChange: (include: Boolean, price: Double, lifetimeMi: Double) -> Unit,
    onInsuranceChange: (perMonth: Double) -> Unit,
    onRegistrationChange: (perYear: Double) -> Unit,
    onPhoneChange: (total: Double, lines: Int, dashPercent: Double) -> Unit,
    onExpectedAnnualMilesChange: (miles: Double) -> Unit,
) {
    val userSet = economy.userSetFields

    // -------- Tires --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_tires_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.tireCostPerMile)),
        isUserSet = EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_tires_cost_label),
            costValue = economy.tireSetCost,
            onCostChange = { onTiresChange(it, economy.tireLifetimeMi) },
            intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
            intervalValue = economy.tireLifetimeMi,
            onIntervalChange = { onTiresChange(economy.tireSetCost, it) },
        )
    }

    // -------- Oil changes --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_oil_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.oilCostPerMile)),
        isUserSet = EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_each_label),
            costValue = economy.oilCost,
            onCostChange = { onOilChange(it, economy.oilIntervalMi) },
            intervalLabel = stringResource(R.string.economy_editor_every_label),
            intervalValue = economy.oilIntervalMi,
            onIntervalChange = { onOilChange(economy.oilCost, it) },
        )
    }

    // -------- Brakes --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_brakes_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.brakesCostPerMile)),
        isUserSet = EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_each_label),
            costValue = economy.brakesCost,
            onCostChange = { onBrakesChange(it, economy.brakesIntervalMi) },
            intervalLabel = stringResource(R.string.economy_editor_every_label),
            intervalValue = economy.brakesIntervalMi,
            onIntervalChange = { onBrakesChange(economy.brakesCost, it) },
        )
    }

    // -------- Fluids --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_fluids_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.fluidsCostPerMile)),
        isUserSet = EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_each_label),
            costValue = economy.fluidsCost,
            onCostChange = { onFluidsChange(it, economy.fluidsIntervalMi) },
            intervalLabel = stringResource(R.string.economy_editor_every_label),
            intervalValue = economy.fluidsIntervalMi,
            onIntervalChange = { onFluidsChange(economy.fluidsCost, it) },
        )
    }

    // -------- Misc repairs --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_misc_title),
        summary = stringResource(R.string.economy_editor_per_mile_format, Formats.money3(economy.miscCostPerMile)),
        isUserSet = EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = stringResource(R.string.economy_editor_misc_cost_label),
            costValue = economy.miscYearly,
            onCostChange = { onMiscChange(it, economy.miscYearlyMi) },
            intervalLabel = stringResource(R.string.economy_editor_misc_interval_label),
            intervalValue = economy.miscYearlyMi,
            onIntervalChange = { onMiscChange(economy.miscYearly, it) },
            intervalSuffix = stringResource(R.string.economy_editor_misc_interval_suffix),
            helperText = stringResource(R.string.economy_editor_misc_helper),
        )
    }

    // -------- Depreciation --------
    EconomyAccordionRow(
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
            Switch(
                checked = economy.includeDepreciation,
                onCheckedChange = {
                    onDepreciationChange(it, economy.purchasePrice, economy.totalLifetimeMi)
                },
            )
        }
        if (economy.includeDepreciation) {
            PairedCurrencyAndIntervalInput(
                costLabel = stringResource(R.string.economy_editor_purchase_price_label),
                costValue = economy.purchasePrice,
                onCostChange = { onDepreciationChange(true, it, economy.totalLifetimeMi) },
                intervalLabel = stringResource(R.string.economy_editor_lifetime_label),
                intervalValue = economy.totalLifetimeMi,
                onIntervalChange = { onDepreciationChange(true, economy.purchasePrice, it) },
                helperText = stringResource(R.string.economy_editor_depreciation_helper),
            )
        }
    }

    // -------- Insurance --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_insurance_title),
        summary = stringResource(R.string.economy_editor_per_mile_asterisk_format, Formats.money3(economy.insuranceCostPerMile)),
        isUserSet = EconomyField.INSURANCE_DELTA in userSet,
    ) {
        CurrencyInput(
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
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_registration_title),
        summary = stringResource(R.string.economy_editor_per_mile_asterisk_format, Formats.money3(economy.registrationCostPerMile)),
        isUserSet = EconomyField.REGISTRATION_DELTA in userSet,
    ) {
        CurrencyInput(
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
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_phone_title),
        summary = stringResource(R.string.economy_editor_per_mile_asterisk_format, Formats.money3(economy.phoneCostPerMile)),
        isUserSet = EconomyField.PHONE_PLAN_TOTAL in userSet ||
            EconomyField.PHONE_PLAN_LINES in userSet ||
            EconomyField.PHONE_BUSINESS_PERCENT in userSet,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyInput(
                label = stringResource(R.string.economy_editor_phone_plan_total_label),
                value = economy.phonePlanTotal,
                onValueChange = {
                    onPhoneChange(it, economy.phonePlanLines, economy.phoneBusinessPercent)
                },
                suffix = stringResource(R.string.economy_editor_per_month_suffix),
                modifier = Modifier.weight(1f),
            )
            IntegerInput(
                label = stringResource(R.string.economy_editor_phone_lines_label),
                value = economy.phonePlanLines,
                onValueChange = {
                    onPhoneChange(economy.phonePlanTotal, it, economy.phoneBusinessPercent)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.economy_editor_phone_business_percent_format, economy.phoneBusinessPercent.toInt()))
        Slider(
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

    // -------- Expected annual gig miles --------
    EconomyAccordionRow(
        title = stringResource(R.string.economy_editor_annual_miles_title),
        summary = stringResource(R.string.economy_editor_annual_miles_summary_format, Formats.commaInt(economy.expectedAnnualMiles.toInt())),
        isUserSet = EconomyField.EXPECTED_ANNUAL_MI in userSet,
    ) {
        Text(
            text = stringResource(R.string.economy_editor_annual_miles_value_format, Formats.commaInt(economy.expectedAnnualMiles.toInt())),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
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
        VehicleClass.entries.forEach { vc ->
            FilterChip(
                selected = selected == vc,
                onClick = { onClassChange(vc) },
                label = { Text(vc.displayName) },
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
            Text(
                text = stringResource(R.string.economy_editor_irs_rate_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                onDepreciationChange = { _, _, _ -> },
                onInsuranceChange = {},
                onRegistrationChange = {},
                onPhoneChange = { _, _, _ -> },
                onExpectedAnnualMilesChange = {},
            )
            TrueCostFooter(operatingCostPerMile = economy.operatingCostPerMile)
        }
    }
}
