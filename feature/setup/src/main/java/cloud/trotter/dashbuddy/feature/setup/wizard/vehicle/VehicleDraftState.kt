package cloud.trotter.dashbuddy.feature.setup.wizard.vehicle

import cloud.trotter.dashbuddy.domain.evaluation.EconomyDraft
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.*

/** The vehicle form's immutable draft, independent of its host's cost edits. */
data class VehicleDraftState(
    val profile: VehicleProfile = GasCar(),
    val vehicleClass: VehicleClass = VehicleClass.SEDAN,
    val lookupAllowed: Boolean = false,
    // UI-only: showing the MPG control must not alter the vehicle identity.
    val manualMpgEntry: Boolean = false,
    val year: String = "",
    val make: String = "",
    val model: String = "",
    val trim: String = "",
    val mpg: Float = 25.0f,
    val kwhPer100Mi: Double = VehiclePresets.EV_KWH_PER_100_MI,
    val whPerMile: Double = VehiclePresets.EBIKE_WH_PER_MILE,
    val electricityPricePerKwh: Double = SharedEconomy.DEFAULT_ELECTRICITY_PRICE_PER_KWH,
    val availableYears: List<String> = emptyList(),
    val availableMakes: List<String> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val availableTrims: List<VehicleOption> = emptyList(),
    /**
     * Only [fields]; disjoint by construction from WizardState.userSetEconomyFields:
     * loading intersects [fields], and draft actions only mark these five fields.
     */
    val userSetFields: Set<EconomyField> = emptySet(),
) {
    val trimNames: List<String> = availableTrims.map { it.displayName }

    /** Apply vehicle edits without applying any host-owned cost groups. */
    fun profile(): VehicleProfile {
        var edited = if (profile.hasCarIdentity) profile.withIdentity(
            year.takeIf { it.isNotBlank() }, make.takeIf { it.isNotBlank() }, model.takeIf { it.isNotBlank() },
        ) else profile
        if (EconomyField.VEHICLE_MPG in userSetFields) edited = edited.withGas(mpg = mpg.toDouble())
        val consumptionFields = setOf(EconomyField.KWH_PER_100_MI, EconomyField.WH_PER_MILE)
        val draft = EconomyDraft(kwhPer100Mi = kwhPer100Mi, whPerMile = whPerMile,
            userSetFields = userSetFields intersect consumptionFields)
        return EconomyFieldGroups.updateProfile(edited, draft)
    }

    companion object {
        fun from(
            profile: VehicleProfile?,
            economy: UserEconomy,
            year: String,
            make: String,
            model: String,
            trim: String,
            mpg: Float,
            lookupAllowed: Boolean,
        ): VehicleDraftState {
            val storedProfile = profile ?: economy.vehicleProfile
            return VehicleDraftState(
                profile = storedProfile,
                vehicleClass = economy.vehicleClass,
                lookupAllowed = lookupAllowed,
                year = year, make = make, model = model, trim = trim,
                manualMpgEntry = year.isNotBlank() && make.isNotBlank() && model.isNotBlank(),
                mpg = mpg,
                kwhPer100Mi = economy.kwhPer100Mi,
                whPerMile = economy.whPerMile,
                electricityPricePerKwh = economy.electricityPricePerKwh,
                // The stored economy's set already carries the profile-derived vehicle fields AND a user-set
                // tariff; reading it keeps the wizard's load exactly as before the split.
                userSetFields = economy.userSetFields intersect fields,
            )
        }

        /** Fields owned by the vehicle form, including the shared electricity tariff. */
        val fields: Set<EconomyField> = setOf(
            EconomyField.VEHICLE_CLASS, EconomyField.VEHICLE_MPG, EconomyField.KWH_PER_100_MI,
            EconomyField.WH_PER_MILE, EconomyField.ELECTRICITY_PRICE,
        )
    }
}
