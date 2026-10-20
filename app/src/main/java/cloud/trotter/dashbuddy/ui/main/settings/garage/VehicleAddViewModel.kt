package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** The Add form keeps the wizard's staged vehicle draft and explicit Add button. */
@HiltViewModel
class VehicleAddViewModel @Inject constructor(
    private val garageRepository: GarageRepository,
    vehicleRepository: VehicleRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {
    private var trimDetails: Pair<List<String>, VehicleDetails>? = null
    val draft = VehicleDraft(vehicleRepository, viewModelScope,
        initialState = VehicleDraftState(), onShapeChanged = { trimDetails = null },
        onTrimDetails = ::rememberTrimDetails)
    private val _name = MutableStateFlow("")
    val name = _name.asStateFlow()
    private val writer = GarageWriter(viewModelScope, "Garage")
    val busy = writer.busy
    val failure = writer.failure
    private val _loaded = MutableStateFlow(false)
    val loaded = _loaded.asStateFlow()
    // If the separate tariff write fails, retry this vehicle instead of adding a duplicate.
    private var addedVehicleId: String? = null
    private var initialElectricityPrice: Double? = null

    init {
        writer.write {
            val garage = garageRepository.garage.first()
            if (!garage.readable) throw VehiclesUnreadableException()
            val shared = appPreferencesRepository.sharedEconomy.first()
            val profile = GasCar()
            val economy = UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)
            val lookupAllowed = appPreferencesRepository.vehicleLookupAllowed.first()
            draft.load(VehicleDraftState.from(
                profile = null, economy = economy,
                year = profile.year.orEmpty(), make = profile.make.orEmpty(), model = profile.model.orEmpty(),
                trim = "", mpg = (profile.gasMpg ?: profile.resolvedConsumption().mpg).toFloat(),
                lookupAllowed = lookupAllowed,
            ))
            initialElectricityPrice = draft.state.value.electricityPricePerKwh
            _loaded.value = true
            true
        }
    }

    fun setName(name: String) {
        if (name.codePointCount(0, name.length) <= Vehicle.MAX_NAME_CODE_POINTS) _name.value = name
    }

    private fun rememberTrimDetails(details: VehicleDetails) {
        trimDetails = draft.state.value.identityKey() to details
    }

    private fun VehicleDraftState.identityKey() = listOf(year, make, model, trim)

    private fun canWrite() = loaded.value && !busy.value &&
        failure.value != GarageWriteFailure.UNREADABLE && failure.value != GarageWriteFailure.GONE

    fun allowLookup() {
        if (!canWrite()) return
        writer.write {
            appPreferencesRepository.setVehicleLookupAllowed(true)
            draft.allowLookup()
            true
        }
    }

    fun save(onDone: () -> Unit) {
        if (!canWrite()) return
        val state = draft.state.value
        val name = _name.value.trim().ifBlank { null }
        var profile = state.profile()
        trimDetails?.takeIf { it.first == state.identityKey() }?.second?.let { details ->
            if (profile.gasFuel != null) profile = profile.withGas(fuelType = details.fuelType)
        }
        writer.write(refused = GarageWriteFailure.GONE, onDone = onDone) {
            val id = addedVehicleId
            if (id == null) {
                addedVehicleId = garageRepository.addVehicle(name, clock.millis(), profile,
                    activateOn = ActivateOn.IF_NONE_ACTIVE).id
            } else {
                if (!garageRepository.updateVehicle(id, name, profile)) return@write false
            }
            if (EconomyField.ELECTRICITY_PRICE in state.userSetFields && state.electricityPricePerKwh != initialElectricityPrice) {
                appPreferencesRepository.updateElectricityPrice(state.electricityPricePerKwh)
            }
            true
        }
    }

}
