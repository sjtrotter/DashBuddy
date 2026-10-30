package cloud.trotter.log.gig.ui.main.settings.garage

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.component.AppCard
import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatMonthDay
import cloud.trotter.log.gig.domain.model.vehicle.*
import java.time.Instant
import java.time.ZoneId

data class VehicleFactsModel(
    val costPerMile: Double, val energyPerMile: Double, val wearPerMile: Double,
    val depreciationPerMile: Double, val fixedPerMile: Double,
    val costs: List<CostLine>,
    val usage: VehicleUsage,
    val lastReading: LastOdometerReading?,
    val retiredAt: Long?,
    val estimated: Boolean = costs.any { it.estimate },
)
data class CostLine(val field: EconomyField, val value: String, val estimate: Boolean)

object VehicleFactsMapper {
    fun map(vehicle: Vehicle, shared: SharedEconomy, usage: VehicleUsage, lastReading: LastOdometerReading?): VehicleFactsModel {
        val profile = vehicle.profile
        val inputs = VehicleEconomyInputs.from(profile, shared)
        val disclosure = requireNotNull(inputs.disclosure)
        val w = disclosure.wear
        val fixed = disclosure.fixed
        val own = EconomyFieldGroups.userSetFields(profile)
        val fields = EconomyFieldGroups.fieldsFor(profile)
        val consumption = disclosure.profile.resolvedConsumption()
        val costs = buildList {
            fun line(field: EconomyField, value: String, estimate: Boolean = field !in own) {
                if (field in fields) add(CostLine(field, value, estimate))
            }
            fun wear(field: EconomyField, interval: EconomyField, cost: Double?, miles: Double?) {
                line(field, "${Formats.money(cost ?: 0.0)} every ${Formats.commaInt((miles ?: 0.0).toInt())} mi",
                    field !in own || interval !in own)
            }
            // EconomyField has no fuel-grade entry; keep grade with the MPG it qualifies.
            line(EconomyField.VEHICLE_MPG, "${Formats.decimal(consumption.mpg)} MPG · ${profile.gasFuel?.displayName.orEmpty()}")
            line(EconomyField.KWH_PER_100_MI, "${Formats.decimal(consumption.kwhPer100Mi)} kWh/100 mi")
            line(EconomyField.WH_PER_MILE, "${Formats.decimal(consumption.whPerMile)} Wh/mi")
            line(EconomyField.GAS_PRICE, "${Formats.money(shared.gasPricePerGallon)}/gal", EconomyField.GAS_PRICE !in shared.userSetFields)
            line(EconomyField.ELECTRICITY_PRICE, "${Formats.money(shared.electricityPricePerKwh)}/kWh", EconomyField.ELECTRICITY_PRICE !in shared.userSetFields)
            wear(EconomyField.TIRE_COST, EconomyField.TIRE_LIFETIME, w.tireSetCost, w.tireLifetimeMi)
            wear(EconomyField.OIL_COST, EconomyField.OIL_INTERVAL, w.oilCost, w.oilIntervalMi)
            wear(EconomyField.BRAKES_COST, EconomyField.BRAKES_INTERVAL, w.brakesCost, w.brakesIntervalMi)
            wear(EconomyField.FLUIDS_COST, EconomyField.FLUIDS_INTERVAL, w.fluidsCost, w.fluidsIntervalMi)
            wear(EconomyField.CHAIN_COST, EconomyField.CHAIN_LIFETIME, w.chainCost, w.chainLifeMi)
            wear(EconomyField.BATTERY_COST, EconomyField.BATTERY_LIFETIME, w.batteryCost, w.batteryLifeMi)
            wear(EconomyField.BELT_COST, EconomyField.BELT_LIFETIME, w.beltCost, w.beltLifeMi)
            wear(EconomyField.MISC_YEARLY, EconomyField.MISC_YEARLY_MI, w.miscYearly, w.miscYearlyMi)
            if (profile.fixed.includeDepreciation != null || profile.fixed.purchasePrice != null || profile.fixed.totalLifetimeMi != null) {
                line(EconomyField.INCLUDE_DEPRECIATION, Formats.money(inputs.depreciationPerMile),
                    fixed.includeDepreciation == true && (profile.fixed.purchasePrice == null || profile.fixed.totalLifetimeMi == null))
            }
            profile.fixed.insuranceDeltaPerMonth?.let {
                line(EconomyField.INSURANCE_DELTA, "${Formats.money(fixed.insuranceDeltaPerMonth ?: 0.0)}/mo", false)
            }
            profile.fixed.registrationDeltaPerYear?.let {
                line(EconomyField.REGISTRATION_DELTA, "${Formats.money(fixed.registrationDeltaPerYear ?: 0.0)}/yr", false)
            }
        }
        val presetDepreciation = fixed.includeDepreciation == true &&
            (profile.fixed.includeDepreciation == null || profile.fixed.purchasePrice == null || profile.fixed.totalLifetimeMi == null)
        return VehicleFactsModel(inputs.energyPerMile + inputs.wearPerMile + inputs.depreciationPerMile + inputs.fixedPerMile,
            inputs.energyPerMile, inputs.wearPerMile, inputs.depreciationPerMile, inputs.fixedPerMile,
            costs, usage, lastReading, vehicle.retiredAt, costs.any { it.estimate } || presetDepreciation)
    }
}

@Composable
fun VehicleFacts(model: VehicleFactsModel, onEditCosts: (() -> Unit)?) {
    AppCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.vehicle_facts_title), style = MaterialTheme.typography.titleMedium)
        FactRow(stringResource(R.string.vehicle_facts_cost_per_mile), estimatedValue(Formats.money(model.costPerMile), model.estimated))
        Column(Modifier.padding(start = 16.dp)) {
            FactRow(stringResource(R.string.vehicle_facts_energy), Formats.money(model.energyPerMile))
            FactRow(stringResource(R.string.vehicle_facts_wear), Formats.money(model.wearPerMile))
            FactRow(stringResource(R.string.vehicle_facts_depreciation), Formats.money(model.depreciationPerMile))
            FactRow(stringResource(R.string.vehicle_facts_fixed), Formats.money(model.fixedPerMile))
        }
        FactsHeading(R.string.vehicle_facts_running_costs)
        model.costs.forEach { cost ->
            FactRow(stringResource(costLabel(cost.field)), estimatedValue(cost.value, cost.estimate))
        }
        FactsHeading(R.string.vehicle_facts_use)
        FactRow(stringResource(R.string.vehicle_facts_dashes), Formats.commaInt(model.usage.dashes))
        FactRow(stringResource(R.string.vehicle_facts_miles_tracked), model.usage.miles?.let { Formats.decimal(it) }
            ?: EMPTY_VALUE)
        Text(stringResource(R.string.vehicle_facts_usage_note), style = MaterialTheme.typography.bodySmall)
        FactRow(stringResource(R.string.vehicle_facts_last_odometer), model.lastReading?.let {
            stringResource(R.string.vehicle_facts_reading, Formats.commaInt(it.readingMiles), vehicleFactsDate(it.occurredAt))
        } ?: stringResource(R.string.vehicle_facts_none_yet))
        model.retiredAt?.let { FactRow(stringResource(R.string.vehicle_facts_retired), vehicleFactsDate(it)) }
        onEditCosts?.let { TextButton(onClick = it) { Text(stringResource(R.string.vehicle_facts_edit_costs)) } }
    }
}

internal fun vehicleFactsDate(millis: Long): String =
    formatMonthDay(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate())

@Composable
private fun estimatedValue(value: String, estimate: Boolean): String =
    if (estimate) stringResource(R.string.vehicle_facts_estimate, value) else value

@Composable
private fun FactsHeading(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun costLabel(field: EconomyField): Int = when (field) {
    EconomyField.VEHICLE_MPG -> R.string.vehicle_facts_fuel_economy
    EconomyField.KWH_PER_100_MI, EconomyField.WH_PER_MILE -> R.string.vehicle_facts_consumption
    EconomyField.GAS_PRICE -> R.string.vehicle_facts_gas_price
    EconomyField.ELECTRICITY_PRICE -> R.string.vehicle_facts_electricity_price
    EconomyField.TIRE_COST -> R.string.vehicle_facts_tires
    EconomyField.OIL_COST -> R.string.vehicle_facts_oil
    EconomyField.BRAKES_COST -> R.string.vehicle_facts_brakes
    EconomyField.FLUIDS_COST -> R.string.vehicle_facts_fluids
    EconomyField.CHAIN_COST -> R.string.vehicle_facts_chain
    EconomyField.BATTERY_COST -> R.string.vehicle_facts_battery
    EconomyField.BELT_COST -> R.string.vehicle_facts_belt
    EconomyField.MISC_YEARLY -> R.string.vehicle_facts_misc
    EconomyField.INCLUDE_DEPRECIATION -> R.string.vehicle_facts_depreciation_per_mile
    EconomyField.INSURANCE_DELTA -> R.string.vehicle_facts_insurance
    EconomyField.REGISTRATION_DELTA -> R.string.vehicle_facts_registration
    else -> error("Not a vehicle facts cost: $field")
}
