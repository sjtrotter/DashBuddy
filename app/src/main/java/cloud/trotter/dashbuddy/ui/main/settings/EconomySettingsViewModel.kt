package cloud.trotter.dashbuddy.ui.main.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Personal Economy settings screen. Pure passthrough to
 * [AppPreferencesRepository] for reads and writes. Each write atomically marks
 * the corresponding [EconomyField] as user-set in DataStore.
 */
@HiltViewModel
class EconomySettingsViewModel @Inject constructor(
    private val repo: AppPreferencesRepository,
) : ViewModel() {

    val userEconomy = repo.userEconomy.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        UserEconomy(),
    )

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

    fun setExpectedAnnualDashMiles(miles: Double) = viewModelScope.launch {
        repo.updateExpectedAnnualDashMi(miles)
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
