package cloud.trotter.dashbuddy.ui.main.settings

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
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.components.economy.TimeConstantsPlatformUiState
import cloud.trotter.dashbuddy.ui.components.economy.TimeConstantsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the Personal Economy settings screen. Pure passthrough to
 * [AppPreferencesRepository] for reads and writes. Each write atomically marks
 * the corresponding [EconomyField] as user-set in DataStore.
 */
@HiltViewModel
class EconomySettingsViewModel @Inject constructor(
    private val repo: AppPreferencesRepository,
    strategyRepository: StrategyRepository,
) : ViewModel() {

    val userEconomy = repo.userEconomy.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserEconomy(),
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
                    automatic = TimeConstants.blend(TimeConstantSeeds.seedFor(platform), config.timeConstants[platform]),
                    effective = TimeConstantPair(resolved.effectiveAvgMinutesPerMile, resolved.effectiveBasePickupMinutes),
                    sampleCount = resolved.timeConstantSampleCount,
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimeConstantsUiState())

    fun setTimeConstantOverride(field: EconomyField, value: Double?) = viewModelScope.launch {
        repo.setTimeConstantOverride(field, value)
    }

    fun setVehicleClass(vc: VehicleClass) = viewModelScope.launch {
        repo.updateVehicleClass(vc)
    }

    fun setTires(setCost: Double, lifetimeMi: Double) = viewModelScope.launch {
        repo.updateTireCost(setCost, lifetimeMi)
    }

    fun setOil(cost: Double, intervalMi: Double) = viewModelScope.launch {
        repo.updateOilCost(cost, intervalMi)
    }

    fun setBrakes(cost: Double, intervalMi: Double) = viewModelScope.launch {
        repo.updateBrakesCost(cost, intervalMi)
    }

    fun setFluids(cost: Double, intervalMi: Double) = viewModelScope.launch {
        repo.updateFluidsCost(cost, intervalMi)
    }

    fun setMiscRepairs(yearly: Double, yearlyMi: Double) = viewModelScope.launch {
        repo.updateMiscMaintenance(yearly, yearlyMi)
    }

    fun setDepreciation(include: Boolean, price: Double, lifetimeMi: Double) = viewModelScope.launch {
        repo.updateDepreciation(include, price, lifetimeMi)
    }

    fun setInsurance(perMonth: Double) = viewModelScope.launch {
        repo.updateInsuranceDelta(perMonth)
    }

    fun setRegistration(perYear: Double) = viewModelScope.launch {
        repo.updateRegistrationDelta(perYear)
    }

    fun setExpectedAnnualMiles(miles: Double) = viewModelScope.launch {
        repo.updateExpectedAnnualMi(miles)
    }

    fun setPhone(total: Double, lines: Int, dashPercent: Double) = viewModelScope.launch {
        repo.updatePhonePlan(total, lines, dashPercent)
    }

    fun setTimeConstants(avgMinPerMile: Double, basePickupMin: Double) = viewModelScope.launch {
        repo.updateTimeConstants(avgMinPerMile, basePickupMin)
    }

    fun resetDefaults() = viewModelScope.launch {
        repo.resetEconomyDefaults()
    }
}
