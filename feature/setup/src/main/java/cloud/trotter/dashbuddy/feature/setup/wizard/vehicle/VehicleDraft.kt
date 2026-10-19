package cloud.trotter.dashbuddy.feature.setup.wizard.vehicle

import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.setup.wizard.VEHICLE_NOT_LISTED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface VehicleDraftActions {
    fun updateVehicleType(type: VehicleType)
    fun updatePowertrain(powertrain: Powertrain)
    fun updateVehicleClass(type: VehicleClass)
    fun updateConsumption(value: Double)
    fun updateElectricityPrice(value: Double)
    fun onManualMpgEntry()
    fun onYearSelected(year: String)
    fun onMakeSelected(make: String)
    fun onModelSelected(model: String)
    fun onTrimSelected(trimName: String)
    fun updateEstimatedMpg(mpg: Float)
}

class VehicleDraft(
    private val lookup: VehicleRepository,
    private val scope: CoroutineScope,
    private val holder: MutableStateFlow<VehicleDraftState>,
    private val onShapeChanged: () -> Unit,
    private val onTrimDetails: (VehicleDetails) -> Unit = {},
    private val onTypeChanged: () -> Unit = onShapeChanged,
) : VehicleDraftActions {
    val state: StateFlow<VehicleDraftState> = holder.asStateFlow()

    fun allowLookup() {
        holder.update { it.copy(lookupAllowed = true) }
        fetchYears()
    }

    fun fetchYears() {
        scope.launch {
            if (!holder.value.lookupAllowed || !holder.value.profile.hasCarIdentity) return@launch
            val years = lookup.getYears()
            holder.update { it.copy(availableYears = years) }
        }
    }

    override fun onManualMpgEntry() {
        holder.update { it.copy(manualMpgEntry = true) }
    }

    override fun updateVehicleType(type: VehicleType) {
        var changed = false
        holder.update { state ->
            changed = state.profile.vehicleType != type
            if (!changed) return@update state
            // The shared electricity tariff survives type changes, as in the wizard.
            state.copy(profile = type.defaultProfile(), vehicleClass = type.defaultProfile().legacyClass,
                userSetFields = state.userSetFields - EconomyFieldGroups.vehicleFields)
                .withTypeDefaults()
        }
        if (changed) onTypeChanged()
        if (holder.value.profile.hasCarIdentity) fetchYears()
    }

    override fun updatePowertrain(powertrain: Powertrain) {
        var applied = false
        holder.update { state ->
            val scooter = state.profile as? ScooterMoped
            applied = scooter != null
            if (scooter == null) return@update state
            state.copy(profile = scooter.copy(powertrain = powertrain)).withTypeDefaults()
        }
        if (applied) onShapeChanged()
    }

    /** Shift defaults to the new class while preserving user-set values and typed costs. */
    override fun updateVehicleClass(type: VehicleClass) {
        val hadIdentity = holder.value.profile.hasCarIdentity
        holder.update { state ->
            val profile = when (val current = state.profile) {
                is HybridCar -> current.copy(vehicleClass = type.gasClass ?: current.vehicleClass)
                else -> LegacyProfile(vehicleClass = type).toTyped().withCosts(current.wear, current.fixed)
            }
            state.copy(profile = profile, vehicleClass = type,
                userSetFields = state.userSetFields + EconomyField.VEHICLE_CLASS).withTypeDefaults()
        }
        onShapeChanged()
        if (!hadIdentity && holder.value.profile.hasCarIdentity) fetchYears()
    }

    private fun VehicleDraftState.withTypeDefaults(): VehicleDraftState {
        val shared = SharedEconomy()
        val resolved = UserEconomy.from(VehicleEconomyInputs.from(profile(), shared), shared)
        return copy(mpg = resolved.vehicleMpg.toFloat(),
            kwhPer100Mi = resolved.kwhPer100Mi, whPerMile = resolved.whPerMile)
    }

    override fun updateConsumption(value: Double) = holder.update {
        if (it.profile is EBike) it.copy(whPerMile = value,
            userSetFields = it.userSetFields + EconomyField.WH_PER_MILE)
        else it.copy(kwhPer100Mi = value, userSetFields = it.userSetFields + EconomyField.KWH_PER_100_MI)
    }

    override fun updateElectricityPrice(value: Double) = holder.update {
        it.copy(electricityPricePerKwh = value, userSetFields = it.userSetFields + EconomyField.ELECTRICITY_PRICE)
    }

    override fun onYearSelected(year: String) {
        if (!holder.value.lookupAllowed || year !in holder.value.availableYears) return
        holder.update {
            it.copy(year = year, make = "", model = "", trim = "",
                availableMakes = emptyList(), availableModels = emptyList(), availableTrims = emptyList())
        }
        scope.launch {
            val makes = listOf(VEHICLE_NOT_LISTED) + lookup.getMakes(year)
            holder.update { it.copy(availableMakes = makes) }
        }
    }

    override fun onMakeSelected(make: String) {
        if (make != VEHICLE_NOT_LISTED &&
            (!holder.value.lookupAllowed || holder.value.year !in holder.value.availableYears)
        ) return
        holder.update { it.copy(make = make, model = "", trim = "",
            availableModels = emptyList(), availableTrims = emptyList()) }

        // Skip API call if Not Listed
        if (make != VEHICLE_NOT_LISTED) {
            scope.launch {
                val models = listOf(VEHICLE_NOT_LISTED) + lookup.getModels(holder.value.year, make)
                holder.update { it.copy(availableModels = models) }
            }
        }
    }

    override fun onModelSelected(model: String) {
        if (model != VEHICLE_NOT_LISTED &&
            (!holder.value.lookupAllowed || holder.value.year !in holder.value.availableYears)
        ) return
        holder.update { it.copy(model = model, trim = "", availableTrims = emptyList()) }

        // Skip API call if Not Listed
        if (model != VEHICLE_NOT_LISTED) {
            scope.launch {
                val trims = listOf(
                    VehicleOption(id = "NOT_LISTED", displayName = VEHICLE_NOT_LISTED),
                ) + lookup.getVehicleOptions(holder.value.year, holder.value.make, model)
                holder.update { it.copy(availableTrims = trims) }
            }
        }
    }

    override fun onTrimSelected(trimName: String) {
        holder.update { it.copy(trim = trimName) }

        // Escape hatch! Don't look up MPG if Not Listed.
        if (trimName == VEHICLE_NOT_LISTED || !holder.value.lookupAllowed) return

        // The lookup answers for THIS identity; a type or identity change while it is in flight wins.
        val lookedUp = holder.value.let { Triple(it.year, it.make, it.model) }
        scope.launch {
            val vehicleId = holder.value.availableTrims.find { it.displayName == trimName }?.id ?: return@launch
            val details = lookup.getVehicleDetails(vehicleId)

            if (details != null) {
                var applied = false
                holder.update { currentState ->
                    val stale = !currentState.profile.hasCarIdentity || currentState.trim != trimName ||
                        Triple(currentState.year, currentState.make, currentState.model) != lookedUp
                    applied = !stale
                    if (stale) return@update currentState
                    if (details.fuelType == FuelType.ELECTRICITY) {
                        val current = currentState.profile()
                        val consumption = details.combinedMpg?.let { VehiclePresets.MPGE_TO_KWH_PER_100_MI / it }
                        return@update currentState.copy(
                            profile = ElectricCar(kwhPer100Mi = consumption,
                                year = current.year, make = current.make, model = current.model)
                                .withCosts(current.wear, current.fixed),
                            vehicleClass = VehicleClass.EV,
                            kwhPer100Mi = consumption ?: VehiclePresets.EV_KWH_PER_100_MI,
                            userSetFields = (currentState.userSetFields - EconomyField.VEHICLE_MPG).let {
                                if (consumption != null) it + EconomyField.KWH_PER_100_MI else it
                            },
                        ).withTypeDefaults()
                    }
                    currentState.copy(
                        mpg = details.combinedMpg ?: currentState.mpg,
                        userSetFields = if (details.combinedMpg != null) {
                            currentState.userSetFields + EconomyField.VEHICLE_MPG
                        } else currentState.userSetFields,
                    )
                }
                if (applied) {
                    if (details.fuelType == FuelType.ELECTRICITY) onShapeChanged()
                    else onTrimDetails(details)
                }
            }
        }
    }

    override fun updateEstimatedMpg(mpg: Float) {
        holder.update {
            it.copy(mpg = mpg, userSetFields = it.userSetFields + EconomyField.VEHICLE_MPG)
        }
    }
}
