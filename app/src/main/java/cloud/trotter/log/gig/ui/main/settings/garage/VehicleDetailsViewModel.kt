package cloud.trotter.log.gig.ui.main.settings.garage

import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.ui.components.economy.LastSaveUndo
import cloud.trotter.log.gig.ui.components.economy.LastSaveUndoState
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.core.data.settings.AppPreferencesRepository
import cloud.trotter.log.gig.core.data.settings.VehicleCostTransforms
import cloud.trotter.log.gig.core.data.vehicle.*
import cloud.trotter.log.gig.domain.evaluation.UserEconomy
import cloud.trotter.log.gig.domain.evaluation.VehicleEconomyInputs
import cloud.trotter.log.gig.domain.model.vehicle.*
import cloud.trotter.log.gig.state.vehicle.VehicleSwapLock
import cloud.trotter.log.gig.ui.main.navigation.Screen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.*
import cloud.trotter.log.gig.ui.components.economy.EditSession
import kotlinx.coroutines.channels.Channel
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

    val edits = EditSession(viewModelScope)
    private val undoState = LastSaveUndoState(writer, edits)
    val lastSaveUndo = undoState.lastSave
    fun undo(save: LastSaveUndo) = if (canEdit()) undoState.undo(save) else rejected()
    fun dismissUndo(save: LastSaveUndo) = undoState.dismiss(save)
    fun clearUndo() = undoState.clear()
    fun enterScreen() = undoState.resume()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var finishing = false
    private var retirementQueued = false
    private val lockedRetire = Channel<Unit>(Channel.BUFFERED)
    val retireLocked = lockedRetire.receiveAsFlow()

    private fun canWrite() = vehicle.value != null &&
        failure.value != GarageWriteFailure.UNREADABLE && failure.value != GarageWriteFailure.GONE
    private fun canEdit() = canWrite() && !retired.value && (!_busy.value || finishing)
    private fun rejected() = CompletableDeferred(WriteResult.REJECTED)

    fun nameField(): EditSession.Field = edits.field("NAME", vehicle.value?.name.orEmpty(), { true }) { text ->
        var captured: LastSaveUndo? = null
        val name = text.trim().ifBlank { null }
        if (canEdit()) undoState.write {
            val saved = garageRepository.updateName(id) { stored ->
                if (captured == null) captured = LastSaveUndo.checked("NAME", R.string.vehicle_edit_name, stored) { old, current ->
                    var matched = false
                    val exists = garageRepository.updateName(id) {
                        if (current() && it == name) { matched = true; old } else it
                    }
                    exists && matched
                }
                name
            }
            if (saved) captured else null
        } else rejected()
    }
    fun setName(value: String) {
        if (canEdit()) { nameField(); edits.edit("NAME", value.cappedVehicleName()) }
    }
    fun nameFocusChanged(focused: Boolean) {
        val field = nameField()
        if (field.focused && !focused) edits.finish("NAME")
        edits.focus("NAME", focused)
    }
    fun flushName() { edits.finish("NAME") }

    private fun <T> edit(key: String, label: Int, previous: (VehicleProfile) -> T,
        transform: (VehicleProfile) -> VehicleProfile,
        restore: (VehicleProfile, T, T) -> VehicleProfile?): Deferred<WriteResult> {
        if (!canEdit()) return rejected()
        var captured: LastSaveUndo? = null
        return undoState.write {
            val saved = garageRepository.updateProfile(id) { stored ->
                val updated = transform(stored)
                if (captured == null) {
                    val prior = previous(stored)
                    val expected = previous(updated)
                    captured = LastSaveUndo.checked(key, label, prior) { old, current ->
                        var matched = false
                        val exists = garageRepository.updateProfile(id) {
                            val restored = if (current()) restore(it, old, expected) else null
                            if (restored != null) { matched = true; restored } else it
                        }
                        exists && matched
                    }
                }
                updated
            }
            if (saved) captured else null
        }
    }

    private fun VehicleProfile.consumptionOverride(): Double? = when (this) {
        is ElectricCar -> kwhPer100Mi
        is ScooterMoped -> kwhPer100Mi
        is EBike -> whPerMile
        else -> null
    }

    fun setTireCost(value: Double) = edit("TIRE_COST", R.string.economy_undo_tire_cost,
        { it.wear.tireSetCost }, { VehicleCostTransforms.tireCost(it, value) }, VehicleCostTransforms::restoreTireCost)
    fun setTireLifetime(value: Double) = edit("TIRE_LIFETIME", R.string.economy_undo_tire_lifetime,
        { it.wear.tireLifetimeMi }, { VehicleCostTransforms.tireLifetime(it, value) }, VehicleCostTransforms::restoreTireLifetime)
    fun setOilCost(value: Double) = edit("OIL_COST", R.string.economy_undo_oil_cost,
        { it.wear.oilCost }, { VehicleCostTransforms.oilCost(it, value) }, VehicleCostTransforms::restoreOilCost)
    fun setOilInterval(value: Double) = edit("OIL_INTERVAL", R.string.economy_undo_oil_interval,
        { it.wear.oilIntervalMi }, { VehicleCostTransforms.oilInterval(it, value) }, VehicleCostTransforms::restoreOilInterval)
    fun setBrakesCost(value: Double) = edit("BRAKES_COST", R.string.economy_undo_brakes_cost,
        { it.wear.brakesCost }, { VehicleCostTransforms.brakesCost(it, value) }, VehicleCostTransforms::restoreBrakesCost)
    fun setBrakesInterval(value: Double) = edit("BRAKES_INTERVAL", R.string.economy_undo_brakes_interval,
        { it.wear.brakesIntervalMi }, { VehicleCostTransforms.brakesInterval(it, value) }, VehicleCostTransforms::restoreBrakesInterval)
    fun setFluidsCost(value: Double) = edit("FLUIDS_COST", R.string.economy_undo_fluids_cost,
        { it.wear.fluidsCost }, { VehicleCostTransforms.fluidsCost(it, value) }, VehicleCostTransforms::restoreFluidsCost)
    fun setFluidsInterval(value: Double) = edit("FLUIDS_INTERVAL", R.string.economy_undo_fluids_interval,
        { it.wear.fluidsIntervalMi }, { VehicleCostTransforms.fluidsInterval(it, value) }, VehicleCostTransforms::restoreFluidsInterval)
    fun setMiscYearly(value: Double) = edit("MISC_YEARLY", R.string.economy_editor_misc_cost_label,
        { it.wear.miscYearly }, { VehicleCostTransforms.miscYearly(it, value) }, VehicleCostTransforms::restoreMiscYearly)
    fun setMiscMiles(value: Double) = edit("MISC_YEARLY_MI", R.string.economy_editor_misc_interval_label,
        { it.wear.miscYearlyMi }, { VehicleCostTransforms.miscMiles(it, value) }, VehicleCostTransforms::restoreMiscMiles)
    fun setPurchasePrice(value: Double) = edit("PURCHASE_PRICE", R.string.economy_editor_purchase_price_label,
        { it.fixed.purchasePrice }, { VehicleCostTransforms.purchasePrice(it, value) }, VehicleCostTransforms::restorePurchasePrice)
    fun setLifetimeMiles(value: Double) = edit("TOTAL_LIFETIME_MI", R.string.economy_undo_lifetime_miles,
        { it.fixed.totalLifetimeMi }, { VehicleCostTransforms.lifetimeMiles(it, value) }, VehicleCostTransforms::restoreLifetimeMiles)
    fun setInsurance(value: Double) = edit("INSURANCE_DELTA", R.string.economy_editor_insurance_title,
        { it.fixed.insuranceDeltaPerMonth }, { VehicleCostTransforms.insurance(it, value) }, VehicleCostTransforms::restoreInsurance)
    fun setRegistration(value: Double) = edit("REGISTRATION_DELTA", R.string.economy_editor_registration_title,
        { it.fixed.registrationDeltaPerYear }, { VehicleCostTransforms.registration(it, value) }, VehicleCostTransforms::restoreRegistration)
    fun setExpectedAnnualMiles(value: Double) = edit("EXPECTED_ANNUAL_MI", R.string.economy_editor_annual_miles_title,
        { it.fixed.expectedAnnualMiles }, { VehicleCostTransforms.annualMiles(it, value) }, VehicleCostTransforms::restoreAnnualMiles)
    fun setChainCost(value: Double) = edit("CHAIN_COST", R.string.economy_undo_chain_cost,
        { it.wear.chainCost }, { VehicleCostTransforms.chainCost(it, value) }, VehicleCostTransforms::restoreChainCost)
    fun setChainLifetime(value: Double) = edit("CHAIN_LIFETIME", R.string.economy_undo_chain_lifetime,
        { it.wear.chainLifeMi }, { VehicleCostTransforms.chainLifetime(it, value) }, VehicleCostTransforms::restoreChainLifetime)
    fun setBatteryCost(value: Double) = edit("BATTERY_COST", R.string.economy_undo_battery_cost,
        { it.wear.batteryCost }, { VehicleCostTransforms.batteryCost(it, value) }, VehicleCostTransforms::restoreBatteryCost)
    fun setBatteryLifetime(value: Double) = edit("BATTERY_LIFETIME", R.string.economy_undo_battery_lifetime,
        { it.wear.batteryLifeMi }, { VehicleCostTransforms.batteryLifetime(it, value) }, VehicleCostTransforms::restoreBatteryLifetime)
    fun setBeltCost(value: Double) = edit("BELT_COST", R.string.economy_undo_belt_cost,
        { it.wear.beltCost }, { VehicleCostTransforms.beltCost(it, value) }, VehicleCostTransforms::restoreBeltCost)
    fun setBeltLifetime(value: Double) = edit("BELT_LIFETIME", R.string.economy_undo_belt_lifetime,
        { it.wear.beltLifeMi }, { VehicleCostTransforms.beltLifetime(it, value) }, VehicleCostTransforms::restoreBeltLifetime)
    fun setConsumption(value: Double) = edit("CONSUMPTION", if (vehicle.value?.profile is EBike) R.string.vehicle_wh_per_mile else R.string.vehicle_kwh_per_100_mi,
        { it.consumptionOverride() }, { VehicleCostTransforms.consumption(it, value) }, VehicleCostTransforms::restoreConsumption)
    fun setMpg(value: Double) = edit("VEHICLE_MPG", R.string.economy_editor_mpg_label,
        { it.gasMpg }, { VehicleCostTransforms.mpg(it, value) }, VehicleCostTransforms::restoreMpg)
    fun setDepreciationInclude(value: Boolean) = edit("INCLUDE_DEPRECIATION", R.string.economy_editor_depreciation_include_label,
        { it.fixed.includeDepreciation }, { VehicleCostTransforms.depreciationInclude(it, value) }, VehicleCostTransforms::restoreDepreciationInclude)
    fun setFuelType(value: FuelType) = edit("FUEL_TYPE", R.string.economy_editor_fuel_type_title,
        { it.gasFuel ?: FuelType.REGULAR }, { VehicleCostTransforms.fuel(it, value) }, VehicleCostTransforms::restoreFuel)
    fun setOdometerShown(shown: Boolean): Deferred<WriteResult> {
        if (!canEdit()) return rejected()
        var captured: LastSaveUndo? = null
        return undoState.write {
            val saved = garageRepository.updateOdometerShown(id) { stored ->
                if (captured == null) captured = LastSaveUndo.checked("ODOMETER", R.string.vehicle_odometer_title, stored) { old, current ->
                    var matched = false
                    val exists = garageRepository.updateOdometerShown(id) {
                        if (current() && it == shown) { matched = true; old } else it
                    }
                    exists && matched
                }
                shown
            }
            if (saved) captured else null
        }
    }

    fun retry() { writer.retry() }
    fun resumeEditing() { undoState.resume(); _busy.value = false; edits.acceptingEdits = true }
    suspend fun discardChanges() {
        clearUndo()
        edits.discardAll()
        writer.discard()
        retirementQueued = false
    }

    /** Accept every unfinished field before the first suspension, including the name. */
    suspend fun finishAndAwait(): GarageWriteFailure = withContext(NonCancellable) {
        clearUndo()
        _busy.value = true
        edits.acceptingEdits = false
        finishing = true
        val submissions = try { edits.submitAll() } finally { finishing = false }
        val finished = submissions.map { it.await() }.all { it == WriteResult.SAVED }
        val result = writer.awaitIdle()
        when {
            !finished && result == GarageWriteFailure.NONE -> GarageWriteFailure.RETRY
            else -> result
        }
    }

    suspend fun awaitIdle(): GarageWriteFailure = writer.awaitIdle()

    suspend fun resetEstimates(): GarageWriteFailure = withContext(NonCancellable) {
        clearUndo()
        if (!canEdit()) return@withContext GarageWriteFailure.GONE
        _busy.value = true
        edits.acceptingEdits = false
        edits.discardAll()
        val reset = writer.write { garageRepository.updateProfile(id) { it.withCosts(WearProfile(), FixedCosts()) } }
        val result = writer.awaitIdle()
        resumeEditing()
        if (reset.await() != WriteResult.SAVED && result == GarageWriteFailure.NONE) GarageWriteFailure.RETRY else result
    }

    fun retire(onDone: () -> Unit) = retire(onDone, {})

    fun retire(onDone: () -> Unit, onFailure: () -> Unit) {
        // Retry resumes the retained retirement action; it must never enqueue retirement twice.
        if (retirementQueued) {
            if (_busy.value) return
            _busy.value = true
            edits.acceptingEdits = false
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (writer.awaitIdle() != GarageWriteFailure.NONE) onFailure()
                resumeEditing()
            }
            return
        }
        if (!canEdit()) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (finishAndAwait() != GarageWriteFailure.NONE) {
                onFailure()
                resumeEditing()
                return@launch
            }
            retirementQueued = true
            val result = writer.write {
                val stored = garageRepository.garage.first()
                if (!stored.readable) throw VehiclesUnreadableException()
                if (stored.active?.id == id && lock.locked()) {
                    retirementQueued = false
                    lockedRetire.trySend(Unit)
                    true
                } else garageRepository.retire(id, clock.millis()).also { if (it) onDone() }
            }
            if (result.await() != WriteResult.SAVED) onFailure()
            resumeEditing()
        }
    }

    fun restore(onDone: () -> Unit) {
        if (!canWrite() || !retired.value || _busy.value) return
        undoState.invalidate()
        _busy.value = true
        writer.write {
            try { garageRepository.restore(id).also { if (it) onDone() } }
            finally { resumeEditing() }
        }
    }
}
