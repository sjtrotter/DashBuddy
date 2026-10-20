package cloud.trotter.dashbuddy.ui.main.settings

import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import kotlinx.coroutines.flow.StateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantSeeds
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstants
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import cloud.trotter.dashbuddy.domain.model.vehicle.FuelType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.components.economy.TimeConstantsPlatformUiState
import cloud.trotter.dashbuddy.ui.components.economy.TimeConstantsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch

/**
 * Backs the Personal Economy settings screen. Vehicle-shaped fields are
 * persisted on the active garage profile by [AppPreferencesRepository].
 */
@HiltViewModel
class EconomySettingsViewModel @Inject constructor(
    private val repo: AppPreferencesRepository,
    strategyRepository: StrategyRepository,
    garageRepository: GarageRepository,
) : ViewModel() {

    val activeVehicle: StateFlow<Vehicle?> = garageRepository.garage.map { it.active }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null,
    )

    val userEconomy = repo.userEconomy.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserEconomy(),
    )

    val fuelType = repo.fuelType.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        FuelType.REGULAR,
    )

    val timeConstants = strategyRepository.evaluationConfig.filterNotNull().map { config ->
        val economy = config.userEconomy
        TimeConstantsUiState(
            paceOverride = economy.avgMinutesPerMile.takeIf { economy.isUserSet(EconomyField.AVG_MIN_PER_MILE) },
            overheadOverride = economy.basePickupMinutes.takeIf { economy.isUserSet(EconomyField.BASE_PICKUP_MIN) },
            platforms = Platform.entries.filter { it != Platform.Unknown }.map { platform ->
                val resolved = config.forPlatform(platform).userEconomy
                TimeConstantsPlatformUiState(
                    platform = platform,
                    automatic = TimeConstants.blend(config.travelSeedFor(platform), config.timeConstants[platform]),
                    effective = TimeConstantPair(resolved.effectiveAvgMinutesPerMile, resolved.effectiveBasePickupMinutes),
                    sampleCount = resolved.timeConstantSampleCount,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimeConstantsUiState())

    fun setTimeConstantOverride(field: EconomyField, value: Double?) = viewModelScope.launch(NonCancellable) {
        repo.setTimeConstantOverride(field, value)
    }

    fun setVehicleClass(vc: VehicleClass) = viewModelScope.launch(NonCancellable) {
        repo.updateVehicleClass(vc)
    }

    fun setMpg(mpg: Double) = viewModelScope.launch(NonCancellable) {
        if (mpg.isFinite() && mpg > 0.0 && mpg <= Float.MAX_VALUE) {
            repo.updateEstimatedMpg(mpg.toFloat())
        }
    }

    fun setFuelType(fuelType: FuelType) = viewModelScope.launch(NonCancellable) {
        repo.updateFuelType(fuelType)
    }

    fun setChain(cost: Double?, life: Double?) = viewModelScope.launch(NonCancellable) { repo.updateChain(cost, life) }
    fun setBattery(cost: Double?, life: Double?) = viewModelScope.launch(NonCancellable) { repo.updateBattery(cost, life) }
    fun setBelt(cost: Double?, life: Double?) = viewModelScope.launch(NonCancellable) { repo.updateBelt(cost, life) }
    fun setConsumption(value: Double) = viewModelScope.launch(NonCancellable) { repo.updateConsumption(value) }
    fun setElectricityPrice(value: Double) = viewModelScope.launch(NonCancellable) { repo.updateElectricityPrice(value) }

    fun setTires(setCost: Double?, lifetimeMi: Double?) = viewModelScope.launch(NonCancellable) {
        repo.updateTireCost(setCost, lifetimeMi)
    }

    fun setOil(cost: Double?, intervalMi: Double?) = viewModelScope.launch(NonCancellable) {
        repo.updateOilCost(cost, intervalMi)
    }

    fun setBrakes(cost: Double?, intervalMi: Double?) = viewModelScope.launch(NonCancellable) {
        repo.updateBrakesCost(cost, intervalMi)
    }

    fun setFluids(cost: Double?, intervalMi: Double?) = viewModelScope.launch(NonCancellable) {
        repo.updateFluidsCost(cost, intervalMi)
    }

    fun setMiscRepairs(yearly: Double?, yearlyMi: Double?) = viewModelScope.launch(NonCancellable) {
        repo.updateMiscMaintenance(yearly, yearlyMi)
    }

    fun setDepreciationInclude(include: Boolean) = viewModelScope.launch(NonCancellable) {
        repo.updateDepreciationInclude(include)
    }

    fun setDepreciationValues(price: Double?, lifetimeMi: Double?) = viewModelScope.launch(NonCancellable) {
        repo.updateDepreciationValues(price, lifetimeMi)
    }

    fun setInsurance(perMonth: Double) = viewModelScope.launch(NonCancellable) {
        repo.updateInsuranceDelta(perMonth)
    }

    fun setRegistration(perYear: Double) = viewModelScope.launch(NonCancellable) {
        repo.updateRegistrationDelta(perYear)
    }

    fun setExpectedAnnualMiles(miles: Double) = viewModelScope.launch(NonCancellable) {
        repo.updateExpectedAnnualMi(miles)
    }

    fun setPhone(total: Double, lines: Int, dashPercent: Double) = viewModelScope.launch(NonCancellable) {
        repo.updatePhonePlan(total, lines, dashPercent)
    }

    fun setTimeConstants(avgMinPerMile: Double, basePickupMin: Double) = viewModelScope.launch(NonCancellable) {
        repo.updateTimeConstants(avgMinPerMile, basePickupMin)
    }

    fun resetDefaults() = viewModelScope.launch(NonCancellable) {
        repo.resetEconomyDefaults()
    }
}
