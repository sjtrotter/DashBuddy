package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Edits one garage vehicle, which need not be active. Never writes the legacy app_prefs
 * mirrors (mirrorEconomySettings and friends): those describe the wizard's single car.
 * Only lookup consent and the shared electricity tariff belong to AppPreferencesRepository.
 */
@HiltViewModel
class VehicleEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garageRepository: GarageRepository,
    vehicleRepository: VehicleRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {
    private val vehicleId: String? = savedStateHandle[Screen.VehicleEdit.ARG]
    val isEditing = vehicleId != null
    val draft = VehicleDraft(vehicleRepository, viewModelScope,
        initialState = VehicleDraftState(), onShapeChanged = {}, onTrimDetails = {})
    private val _name = MutableStateFlow("")
    val name = _name.asStateFlow()
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _failure = MutableStateFlow(GarageWriteFailure.NONE)
    val failure = _failure.asStateFlow()
    private var loaded = false
    // If the separate tariff write fails, retry this vehicle instead of adding a duplicate.
    private var addedVehicleId: String? = null

    init {
        viewModelScope.launch {
            try {
                val garage = garageRepository.garage.first()
                val vehicle = vehicleId?.let { id -> garage.unretired.firstOrNull { it.id == id } }
                if (!garage.readable || (isEditing && vehicle == null)) throw VehiclesUnreadableException()
                val shared = SharedEconomy.from(appPreferencesRepository.userEconomy.first())
                val profile = vehicle?.profile ?: GasCar()
                val economy = UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)
                val lookupAllowed = appPreferencesRepository.vehicleLookupAllowed.first()
                draft.load(VehicleDraftState.from(
                    profile = vehicle?.profile, economy = economy,
                    year = profile.year.orEmpty(), make = profile.make.orEmpty(), model = profile.model.orEmpty(),
                    trim = "", mpg = (profile.gasMpg ?: profile.resolvedConsumption().mpg).toFloat(),
                    lookupAllowed = lookupAllowed,
                ))
                _name.value = vehicle?.name.orEmpty()
                loaded = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(error)
            } finally {
                _busy.value = false
            }
        }
    }

    fun setName(name: String) { _name.value = name }

    fun allowLookup() {
        if (!loaded || _busy.value || _failure.value == GarageWriteFailure.UNREADABLE) return
        viewModelScope.launch {
            try {
                appPreferencesRepository.setVehicleLookupAllowed(true)
                draft.allowLookup()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(error)
            }
        }
    }

    fun save(onDone: () -> Unit) {
        if (!loaded || _busy.value || _failure.value == GarageWriteFailure.UNREADABLE) return
        _busy.value = true
        val state = draft.state.value
        val name = _name.value.trim().ifBlank { null }
        viewModelScope.launch {
            try {
                val wrote = withContext(NonCancellable) {
                    // Validate before updateProfile so an invalid rename cannot partially edit a vehicle.
                    require(name == null || name.codePointCount(0, name.length) <= Vehicle.MAX_NAME_CODE_POINTS)
                    val profile = state.profile()
                    val id = vehicleId ?: addedVehicleId
                    if (id == null) {
                        addedVehicleId = garageRepository.addVehicle(name, clock.millis(), profile).id
                    } else {
                        if (!garageRepository.updateProfile(id, profile)) return@withContext false
                        if (!garageRepository.rename(id, name)) return@withContext false
                    }
                    if (EconomyField.ELECTRICITY_PRICE in state.userSetFields) {
                        appPreferencesRepository.updateElectricityPrice(state.electricityPricePerKwh)
                    }
                    true
                }
                if (wrote) {
                    _failure.value = GarageWriteFailure.NONE
                    onDone()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                recordFailure(error)
            } finally {
                _busy.value = false
            }
        }
    }

    private fun recordFailure(error: Exception) {
        _failure.value = if (error is VehiclesUnreadableException) GarageWriteFailure.UNREADABLE else GarageWriteFailure.RETRY
        Timber.tag("Garage").w("%s", error.javaClass.simpleName)
    }
}
