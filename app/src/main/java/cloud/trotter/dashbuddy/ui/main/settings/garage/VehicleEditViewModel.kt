package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.EconomyFieldGroups
import cloud.trotter.dashbuddy.domain.evaluation.SharedEconomy
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraft
import cloud.trotter.dashbuddy.feature.setup.wizard.vehicle.VehicleDraftState
import cloud.trotter.dashbuddy.state.vehicle.VehicleSwapLock
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Edits one garage vehicle, which need not be active. Never writes the legacy app_prefs
 * mirrors (mirrorEconomySettings and friends): those describe the wizard's single car.
 * Adds keep the active vehicle when one exists; the Garage radio selects another vehicle.
 * Only lookup consent and the shared electricity tariff belong to AppPreferencesRepository.
 */
@HiltViewModel
class VehicleEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garageRepository: GarageRepository,
    vehicleRepository: VehicleRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val clock: Clock,
    private val lock: VehicleSwapLock,
) : ViewModel() {
    private val vehicleId: String? = savedStateHandle[Screen.VehicleEdit.ARG]
    val isEditing = vehicleId != null
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
    private val _vehicle = MutableStateFlow<Vehicle?>(null)
    val vehicle = _vehicle.asStateFlow()
    private val lockedRetire = Channel<Unit>(Channel.BUFFERED)
    val retireLocked = lockedRetire.receiveAsFlow()
    private var storedProfile: VehicleProfile? = null
    // If the separate tariff write fails, retry this vehicle instead of adding a duplicate.
    private var addedVehicleId: String? = null

    init {
        writer.write(refused = GarageWriteFailure.GONE) {
            val garage = garageRepository.garage.first()
            if (!garage.readable) throw VehiclesUnreadableException()
            val vehicle = vehicleId?.let { id -> garage.unretired.firstOrNull { it.id == id } }
            if (isEditing && vehicle == null) return@write false
            val shared = SharedEconomy.from(appPreferencesRepository.userEconomy.first())
            val profile = vehicle?.profile ?: GasCar()
            storedProfile = vehicle?.profile
            val economy = UserEconomy.from(VehicleEconomyInputs.from(profile, shared), shared)
            val lookupAllowed = appPreferencesRepository.vehicleLookupAllowed.first()
            draft.load(VehicleDraftState.from(
                profile = vehicle?.profile, economy = economy,
                year = profile.year.orEmpty(), make = profile.make.orEmpty(), model = profile.model.orEmpty(),
                trim = "", mpg = (profile.gasMpg ?: profile.resolvedConsumption().mpg).toFloat(),
                lookupAllowed = lookupAllowed,
            ))
            _name.value = vehicle?.name.orEmpty()
            _vehicle.value = vehicle
            _loaded.value = true
            true
        }
    }

    fun resetsCosts(type: VehicleType): Boolean = storedProfile?.let { it.vehicleType != type } ?: false

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

    fun retire(onDone: () -> Unit) {
        val id = vehicleId ?: return
        if (!canWrite()) return
        // Review r1: a repository refusal (the vehicle is already retired, e.g. from another editor) reports
        // GONE like Save does; a lock refusal is the toast only, so it neither leaves the screen nor shows GONE.
        var lockedOut = false
        writer.write(refused = GarageWriteFailure.GONE, onDone = { if (!lockedOut) onDone() }) {
            val garage = garageRepository.garage.first()
            if (!garage.readable) throw VehiclesUnreadableException()
            if (garage.active?.id == id && lock.locked()) {
                lockedOut = true
                lockedRetire.trySend(Unit)
                return@write true
            }
            garageRepository.retire(id, clock.millis())
        }
    }

    fun save(onDone: () -> Unit) {
        if (!canWrite()) return
        val state = draft.state.value
        val name = _name.value.trim().ifBlank { null }
        var profile = state.profile()
        storedProfile?.takeIf { it.vehicleType == profile.vehicleType }?.let { stored ->
            // A type round trip clears draft overrides; defaults must not replace stored values.
            val edits = UserEconomy(
                vehicleClass = state.vehicleClass, vehicleMpg = state.mpg.toDouble(),
                kwhPer100Mi = state.kwhPer100Mi, whPerMile = state.whPerMile,
                electricityPricePerKwh = state.electricityPricePerKwh,
                userSetFields = state.userSetFields intersect VehicleDraftState.fields,
            )
            // A scooter's powertrain is part of its shape, not a field group: carry a changed one over
            // so its consumption fields become applicable before the user-set edits are applied.
            val draftProfile = state.profile
            val base = if (stored is ScooterMoped && draftProfile is ScooterMoped && stored.powertrain != draftProfile.powertrain)
                stored.copy(powertrain = draftProfile.powertrain) else stored
            profile = EconomyFieldGroups.updateProfile(base, edits).withIdentity(
                year = state.year.editedIdentity(stored.year),
                make = state.make.editedIdentity(stored.make),
                model = state.model.editedIdentity(stored.model),
            )
        }
        if (resetsCosts(profile.vehicleType)) {
            val presets = profile.vehicleType.defaultProfile()
            profile = profile.withCosts(presets.wear, presets.fixed)
        }
        trimDetails?.takeIf { it.first == state.identityKey() }?.second?.let { details ->
            if (profile.gasFuel != null) profile = profile.withGas(fuelType = details.fuelType)
        }
        writer.write(refused = GarageWriteFailure.GONE, onDone = onDone) {
            val id = vehicleId ?: addedVehicleId
            if (id == null) {
                addedVehicleId = garageRepository.addVehicle(name, clock.millis(), profile,
                    activateOn = ActivateOn.IF_NONE_ACTIVE).id
            } else {
                if (!garageRepository.updateVehicle(id, name, profile)) return@write false
            }
            if (EconomyField.ELECTRICITY_PRICE in state.userSetFields) {
                appPreferencesRepository.updateElectricityPrice(state.electricityPricePerKwh)
            }
            true
        }
    }

    private fun String.editedIdentity(stored: String?): String? =
        if (this == stored.orEmpty()) stored else takeIf { it.isNotBlank() }
}
