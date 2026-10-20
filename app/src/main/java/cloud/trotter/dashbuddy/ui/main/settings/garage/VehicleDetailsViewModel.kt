package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.data.settings.VehicleCostTransforms
import cloud.trotter.dashbuddy.core.data.vehicle.*
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.dashbuddy.domain.model.vehicle.*
import cloud.trotter.dashbuddy.state.vehicle.VehicleSwapLock
import cloud.trotter.dashbuddy.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Observes one saved vehicle. Every edit transforms the profile stored at write time. */
@HiltViewModel
class VehicleDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val garageRepository: GarageRepository,
    private val appPreferencesRepository: AppPreferencesRepository,
    private val clock: Clock,
    private val lock: VehicleSwapLock,
    dashVehicleRepository: DashVehicleRepository,
    odometerReadingRepository: OdometerReadingRepository,
    odometerRepository: OdometerRepository,
) : ViewModel() {
    private val id: String = checkNotNull(savedStateHandle[Screen.VehicleEdit.ARG])
    private val writer = VehicleCostWriter(viewModelScope)
    val pending = writer.pending
    private val garage: StateFlow<Garage?> = garageRepository.garage.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val vehicle = garage.map { it?.vehicles?.firstOrNull { vehicle -> vehicle.id == id } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val loaded = garage.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val retired = vehicle.map { it?.retiredAt != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val failure = combine(garage, writer.failure) { stored, error ->
        when {
            stored?.readable == false -> GarageWriteFailure.UNREADABLE
            stored != null && stored.vehicles.none { it.id == id } -> GarageWriteFailure.GONE
            else -> error
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GarageWriteFailure.NONE)
    val economy = combine(vehicle, appPreferencesRepository.sharedEconomy) { target, shared ->
        target?.let { UserEconomy.from(VehicleEconomyInputs.from(it.profile, shared), shared) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val facts = combine(vehicle, appPreferencesRepository.sharedEconomy, dashVehicleRepository.stamps,
        odometerRepository.totalMilesFlow, odometerReadingRepository.readings,
    ) { target, shared, stamps, gpsNow, readings ->
        target?.let { VehicleFactsMapper.map(it, shared,
            DashVehicles.usageFor(DashVehicles.perDash(stamps), it.id, gpsNow),
            VehicleOdometerReading.latestFor(readings, it.id)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _name = MutableStateFlow("")
    val name = _name.asStateFlow()
    private var nameDirty = false
    private var nameFocused = false
    private var renameJob: Job? = null
    private var statusBusy = false
    private val lockedRetire = Channel<Unit>(Channel.BUFFERED)
    val retireLocked = lockedRetire.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(vehicle, pending) { target, count -> target to count }.collect { (target, count) ->
                if (target != null && !nameDirty && !nameFocused && count == 0) _name.value = target.name.orEmpty()
            }
        }
    }

    private fun canWrite() = vehicle.value != null &&
        failure.value != GarageWriteFailure.UNREADABLE && failure.value != GarageWriteFailure.GONE
    private fun canEdit() = canWrite() && !retired.value

    fun setName(value: String) {
        if (!canEdit()) return
        _name.value = value.cappedVehicleName()
        nameDirty = true
        renameJob?.cancel()
        renameJob = viewModelScope.launch { delay(400); flushName() }
    }

    fun nameFocusChanged(focused: Boolean) {
        if (nameFocused && !focused) flushName()
        nameFocused = focused
        if (!focused && !nameDirty && pending.value == 0) _name.value = vehicle.value?.name.orEmpty()
    }

    fun flushName() {
        renameJob?.cancel()
        renameJob = null
        if (!nameDirty || !canEdit()) return
        nameDirty = false
        val value = _name.value.trim().ifBlank { null }
        writer.write { garageRepository.rename(id, value) }
    }

    private fun edit(transform: (VehicleProfile) -> VehicleProfile) {
        if (canEdit()) writer.write { garageRepository.updateProfile(id, transform) }
    }

    fun setTires(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.tires(it, cost, miles) }
    fun setOil(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.oil(it, cost, miles) }
    fun setBrakes(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.brakes(it, cost, miles) }
    fun setFluids(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.fluids(it, cost, miles) }
    fun setMiscRepairs(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.misc(it, cost, miles) }
    fun setDepreciationInclude(include: Boolean) = edit { VehicleCostTransforms.depreciationInclude(it, include) }
    fun setDepreciationValues(price: Double?, miles: Double?) = edit { VehicleCostTransforms.depreciationValues(it, price, miles) }
    fun setInsurance(value: Double) = edit { VehicleCostTransforms.insurance(it, value) }
    fun setRegistration(value: Double) = edit { VehicleCostTransforms.registration(it, value) }
    fun setExpectedAnnualMiles(value: Double) = edit { VehicleCostTransforms.annualMiles(it, value) }
    fun setChain(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.chain(it, cost, miles) }
    fun setBattery(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.battery(it, cost, miles) }
    fun setBelt(cost: Double?, miles: Double?) = edit { VehicleCostTransforms.belt(it, cost, miles) }
    fun setConsumption(value: Double) = edit { VehicleCostTransforms.consumption(it, value) }
    fun setMpg(value: Double) = edit { VehicleCostTransforms.mpg(it, value) }
    fun setFuelType(value: FuelType) = edit { VehicleCostTransforms.fuel(it, value) }
    fun setElectricityPrice(value: Double) {
        if (canEdit()) writer.write { appPreferencesRepository.updateElectricityPrice(value); true }
    }

    fun retry() = writer.retry()
    suspend fun discardChanges() = writer.discard()
    suspend fun awaitIdle(): GarageWriteFailure {
        flushName()
        writer.awaitIdle()
        return when {
            garage.value?.readable == false -> GarageWriteFailure.UNREADABLE
            garage.value?.vehicles?.none { it.id == id } == true -> GarageWriteFailure.GONE
            else -> writer.failure.value
        }
    }

    fun retire(onDone: () -> Unit) {
        if (!canEdit() || pending.value > 0 || statusBusy) return
        statusBusy = true
        writer.write {
            try {
                val stored = garageRepository.garage.first()
                if (!stored.readable) throw VehiclesUnreadableException()
                if (stored.active?.id == id && lock.locked()) {
                    lockedRetire.trySend(Unit)
                    true
                } else garageRepository.retire(id, clock.millis()).also { if (it) onDone() }
            } finally { statusBusy = false }
        }
    }

    fun restore(onDone: () -> Unit) {
        if (!canWrite() || !retired.value || pending.value > 0 || statusBusy) return
        statusBusy = true
        writer.write {
            try { garageRepository.restore(id).also { if (it) onDone() } }
            finally { statusBusy = false }
        }
    }
}
