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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.format.DashFormats
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
    onExpectedAnnualDashMilesChange: (miles: Double) -> Unit,
) {
    val userSet = economy.userSetFields

    // -------- Tires --------
    EconomyAccordionRow(
        title = "Tires",
        summary = "${DashFormats.money3(economy.tireCostPerMile)}/mi",
        isUserSet = EconomyField.TIRE_COST in userSet || EconomyField.TIRE_LIFETIME in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Set of 4",
            costValue = economy.tireSetCost,
            onCostChange = { onTiresChange(it, economy.tireLifetimeMi) },
            intervalLabel = "Lifetime",
            intervalValue = economy.tireLifetimeMi,
            onIntervalChange = { onTiresChange(economy.tireSetCost, it) },
        )
    }

    // -------- Oil changes --------
    EconomyAccordionRow(
        title = "Oil changes",
        summary = "${DashFormats.money3(economy.oilCostPerMile)}/mi",
        isUserSet = EconomyField.OIL_COST in userSet || EconomyField.OIL_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Each",
            costValue = economy.oilCost,
            onCostChange = { onOilChange(it, economy.oilIntervalMi) },
            intervalLabel = "Every",
            intervalValue = economy.oilIntervalMi,
            onIntervalChange = { onOilChange(economy.oilCost, it) },
        )
    }

    // -------- Brakes --------
    EconomyAccordionRow(
        title = "Brakes",
        summary = "${DashFormats.money3(economy.brakesCostPerMile)}/mi",
        isUserSet = EconomyField.BRAKES_COST in userSet || EconomyField.BRAKES_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Each",
            costValue = economy.brakesCost,
            onCostChange = { onBrakesChange(it, economy.brakesIntervalMi) },
            intervalLabel = "Every",
            intervalValue = economy.brakesIntervalMi,
            onIntervalChange = { onBrakesChange(economy.brakesCost, it) },
        )
    }

    // -------- Fluids --------
    EconomyAccordionRow(
        title = "Fluids",
        summary = "${DashFormats.money3(economy.fluidsCostPerMile)}/mi",
        isUserSet = EconomyField.FLUIDS_COST in userSet || EconomyField.FLUIDS_INTERVAL in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Each",
            costValue = economy.fluidsCost,
            onCostChange = { onFluidsChange(it, economy.fluidsIntervalMi) },
            intervalLabel = "Every",
            intervalValue = economy.fluidsIntervalMi,
            onIntervalChange = { onFluidsChange(economy.fluidsCost, it) },
        )
    }

    // -------- Misc repairs --------
    EconomyAccordionRow(
        title = "Misc repairs",
        summary = "${DashFormats.money3(economy.miscCostPerMile)}/mi",
        isUserSet = EconomyField.MISC_YEARLY in userSet || EconomyField.MISC_YEARLY_MI in userSet,
    ) {
        PairedCurrencyAndIntervalInput(
            costLabel = "Yearly budget",
            costValue = economy.miscYearly,
            onCostChange = { onMiscChange(it, economy.miscYearlyMi) },
            intervalLabel = "Driving",
            intervalValue = economy.miscYearlyMi,
            onIntervalChange = { onMiscChange(economy.miscYearly, it) },
            intervalSuffix = "mi/yr",
            helperText = "Annual catch-all for repairs not in oil/brakes/fluids.",
        )
    }

    // -------- Depreciation --------
    EconomyAccordionRow(
        title = "Depreciation",
        summary = if (economy.includeDepreciation) {
            "${DashFormats.money3(economy.depreciationCostPerMile)}/mi"
        } else {
            "off"
        },
        isUserSet = EconomyField.INCLUDE_DEPRECIATION in userSet ||
            EconomyField.PURCHASE_PRICE in userSet ||
            EconomyField.TOTAL_LIFETIME_MI in userSet,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include depreciation", modifier = Modifier.weight(1f))
            Switch(
                checked = economy.includeDepreciation,
                onCheckedChange = {
                    onDepreciationChange(it, economy.purchasePrice, economy.totalLifetimeMi)
                },
            )
        }
        if (economy.includeDepreciation) {
            PairedCurrencyAndIntervalInput(
                costLabel = "Purchase price",
                costValue = economy.purchasePrice,
                onCostChange = { onDepreciationChange(true, it, economy.totalLifetimeMi) },
                intervalLabel = "Lifetime",
                intervalValue = economy.totalLifetimeMi,
                onIntervalChange = { onDepreciationChange(true, economy.purchasePrice, it) },
                helperText = "Total expected miles from new to retirement, " +
                    "e.g. 200,000 for a typical sedan.",
            )
        }
    }

    // -------- Insurance --------
    EconomyAccordionRow(
        title = "Insurance",
        summary = "${DashFormats.money3(economy.insuranceCostPerMile)}/mi*",
        isUserSet = EconomyField.INSURANCE_DELTA in userSet,
    ) {
        CurrencyInput(
            label = "Extra for gig work",
            value = economy.insuranceDeltaPerMonth,
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
        summary = "${DashFormats.money3(economy.registrationCostPerMile)}/mi*",
        isUserSet = EconomyField.REGISTRATION_DELTA in userSet,
    ) {
        CurrencyInput(
            label = "Commercial delta",
            value = economy.registrationDeltaPerYear,
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
        summary = "${DashFormats.money3(economy.phoneCostPerMile)}/mi*",
        isUserSet = EconomyField.PHONE_PLAN_TOTAL in userSet ||
            EconomyField.PHONE_PLAN_LINES in userSet ||
            EconomyField.PHONE_DASH_PERCENT in userSet,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CurrencyInput(
                label = "Plan total",
                value = economy.phonePlanTotal,
                onValueChange = {
                    onPhoneChange(it, economy.phonePlanLines, economy.phoneDashPercent)
                },
                suffix = "/mo",
                modifier = Modifier.weight(1f),
            )
            IntegerInput(
                label = "Lines",
                value = economy.phonePlanLines,
                onValueChange = {
                    onPhoneChange(economy.phonePlanTotal, it, economy.phoneDashPercent)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text("% for gig work: ${economy.phoneDashPercent.toInt()}%")
        Slider(
            value = economy.phoneDashPercent.toFloat(),
            onValueChange = {
                onPhoneChange(economy.phonePlanTotal, economy.phonePlanLines, it.toDouble())
            },
            valueRange = 0f..100f,
        )
        val perLine = economy.phonePlanTotal / economy.phonePlanLines.coerceAtLeast(1)
        Text(
            text = "Your line: ${DashFormats.money(perLine)}/mo" +
                " × ${economy.phoneDashPercent.toInt()}%" +
                " = ${DashFormats.money(perLine * economy.phoneDashPercent / 100.0)}/mo for gig work",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // -------- Expected annual gig miles --------
    EconomyAccordionRow(
        title = "Expected gig miles / yr",
        summary = "${DashFormats.commaInt(economy.expectedAnnualDashMiles.toInt())} mi",
        isUserSet = EconomyField.EXPECTED_ANNUAL_DASH_MI in userSet,
    ) {
        Text(
            text = "${DashFormats.commaInt(economy.expectedAnnualDashMiles.toInt())} miles per year",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = economy.expectedAnnualDashMiles.toFloat(),
            onValueChange = { onExpectedAnnualDashMilesChange(it.toDouble()) },
            valueRange = 500f..30_000f,
            steps = 58, // 500-mile increments
        )
        Text(
            text = "Used to amortize fixed costs (insurance, registration, phone) into " +
                "a per-mile rate. Bigger number → smaller per-mile share.",
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
        text = "Vehicle class",
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
                text = "Your true cost: ${DashFormats.money(operatingCostPerMile)}/mi",
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
                onExpectedAnnualDashMilesChange = {},
            )
            TrueCostFooter(operatingCostPerMile = economy.operatingCostPerMile)
        }
    }
}
