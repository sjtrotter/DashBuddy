package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.vehicle.ActivateOn
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import cloud.trotter.dashbuddy.core.data.vehicle.DashVehicleRepository
import cloud.trotter.dashbuddy.core.data.vehicle.OdometerReadingRepository
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    dashVehicleRepository: DashVehicleRepository,
    odometerReadingRepository: OdometerReadingRepository,
    odometerRepository: OdometerRepository,
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
    private val garageState: StateFlow<Garage?> = garageRepository.garage
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    // The form loads once; facts follow stored costs when returning from Economy.
    val vehicle: StateFlow<Vehicle?> = combine(_vehicle, garageState) { loadedVehicle, garage ->
        loadedVehicle?.let { loaded -> garage?.vehicles?.firstOrNull { it.id == loaded.id } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val odometerShown: StateFlow<Boolean?> = vehicle.map { it?.showsOdometer }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val retired: StateFlow<Boolean> = vehicle.map { it?.retiredAt != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isActive: StateFlow<Boolean> = garageState.map { vehicleId != null && it?.active?.id == vehicleId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val facts: StateFlow<VehicleFactsModel?> = if (isEditing) combine(
        vehicle, appPreferencesRepository.sharedEconomy, dashVehicleRepository.stamps,
        odometerRepository.totalMilesFlow, odometerReadingRepository.readings,
    ) { loadedVehicle, economy, stamps, gpsNow, readings ->
        loadedVehicle?.let {
            VehicleFactsMapper.map(it, economy,
                DashVehicles.usageFor(DashVehicles.perDash(stamps), it.id, gpsNow),
                VehicleOdometerReading.latestFor(readings, it.id))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null) else MutableStateFlow(null)
    private val lockedRetire = Channel<Unit>(Channel.BUFFERED)
    val retireLocked = lockedRetire.receiveAsFlow()
    private var storedProfile: VehicleProfile? = null
    /** The form as loaded: Save applies only what the dasher changed on this page since (#1383 review r2). */
    private var loadedDraft: VehicleDraftState? = null
    // If the separate tariff write fails, retry this vehicle instead of adding a duplicate.
    private var addedVehicleId: String? = null

    init {
        writer.write(refused = GarageWriteFailure.GONE) {
            val garage = garageRepository.garage.first()
            if (!garage.readable) throw VehiclesUnreadableException()
            val vehicle = vehicleId?.let { id -> garage.vehicles.firstOrNull { it.id == id } }
            if (isEditing && vehicle == null) return@write false
            val shared = appPreferencesRepository.sharedEconomy.first()
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
            loadedDraft = draft.state.value
            _name.value = vehicle?.name.orEmpty()
            _vehicle.value = vehicle
            _loaded.value = true
            true
        }
    }

    /** The vehicle as stored NOW (it follows Economy edits made through Edit costs), else as first loaded. */
    private fun currentProfile(): VehicleProfile? = vehicle.value?.profile ?: storedProfile

    private fun changedFields(state: VehicleDraftState): Set<EconomyField> {
        val loaded = loadedDraft ?: return VehicleDraftState.fields
        return buildSet {
            if (state.vehicleClass != loaded.vehicleClass) add(EconomyField.VEHICLE_CLASS)
            if (state.mpg != loaded.mpg) add(EconomyField.VEHICLE_MPG)
            if (state.kwhPer100Mi != loaded.kwhPer100Mi) add(EconomyField.KWH_PER_100_MI)
            if (state.whPerMile != loaded.whPerMile) add(EconomyField.WH_PER_MILE)
            if (state.electricityPricePerKwh != loaded.electricityPricePerKwh) add(EconomyField.ELECTRICITY_PRICE)
        }
    }

    fun resetsCosts(type: VehicleType): Boolean = currentProfile()?.let { it.vehicleType != type } ?: false

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

    fun setOdometerShown(shown: Boolean) {
        val id = vehicleId ?: return
        if (!canWrite()) return
        writer.write(refused = GarageWriteFailure.GONE) {
            garageRepository.setOdometerShown(id, shown)
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

    fun restore(onDone: () -> Unit) {
        val id = vehicleId ?: return
        if (!canWrite()) return
        writer.write(refused = GarageWriteFailure.GONE, onDone = onDone) {
            garageRepository.restore(id)
        }
    }

    fun save(onDone: () -> Unit) {
        if (!canWrite()) return
        val state = draft.state.value
        // Fields marked as the dasher's own but untouched on this page keep the value stored now: an Edit costs
        // round trip (consumption, the shared electricity tariff) must not be overwritten by the form's stale copy.
        val changed = changedFields(state)
        val name = _name.value.trim().ifBlank { null }
        var profile = state.profile()
        // Merge onto the vehicle as stored now, not as first loaded: an Edit costs round trip must survive a
        // later rename and Save (#1383 review).
        currentProfile()?.takeIf { it.vehicleType == profile.vehicleType }?.let { stored ->
            // A type round trip clears draft overrides; defaults must not replace stored values.
            val edits = UserEconomy(
                vehicleClass = state.vehicleClass, vehicleMpg = state.mpg.toDouble(),
                kwhPer100Mi = state.kwhPer100Mi, whPerMile = state.whPerMile,
                electricityPricePerKwh = state.electricityPricePerKwh,
                userSetFields = state.userSetFields intersect VehicleDraftState.fields intersect changed,
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
            if (EconomyField.ELECTRICITY_PRICE in state.userSetFields && EconomyField.ELECTRICITY_PRICE in changed) {
                appPreferencesRepository.updateElectricityPrice(state.electricityPricePerKwh)
            }
            true
        }
    }

    private fun String.editedIdentity(stored: String?): String? =
        if (this == stored.orEmpty()) stored else takeIf { it.isNotBlank() }
}
