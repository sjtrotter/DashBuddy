package cloud.trotter.dashbuddy.ui.main.settings

import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.domain.model.vehicle.Vehicle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.core.datastore.settings.SaveCapture
import cloud.trotter.dashbuddy.core.datastore.settings.StoredPreference
import cloud.trotter.dashbuddy.core.datastore.settings.StoredGasPrice
import cloud.trotter.dashbuddy.core.data.strategy.StrategyRepository
import cloud.trotter.dashbuddy.domain.evaluation.EconomyField
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstantPair
import cloud.trotter.dashbuddy.domain.evaluation.TimeConstants
import cloud.trotter.dashbuddy.domain.evaluation.UserEconomy
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.components.economy.TimeConstantsPlatformUiState
import cloud.trotter.dashbuddy.ui.components.economy.TimeConstantsUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.*
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.components.economy.LastSaveUndo
import cloud.trotter.dashbuddy.ui.components.economy.LastSaveUndoState
import cloud.trotter.dashbuddy.ui.components.economy.EditSession
import cloud.trotter.dashbuddy.ui.main.settings.garage.*

/** Owns live shared settings; vehicle values are edited by their ID-addressed details page. */
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

    private val writer = VehicleCostWriter(viewModelScope)
    val edits = EditSession(viewModelScope)
    val pending = writer.pending
    val failure = writer.failure
    private val undoState = LastSaveUndoState(writer, edits)
    val lastSaveUndo = undoState.lastSave
    fun undo(save: LastSaveUndo) = if (!_busy.value) undoState.undo(save) else CompletableDeferred(WriteResult.REJECTED)
    fun dismissUndo(save: LastSaveUndo) = undoState.dismiss(save)
    fun clearUndo() = undoState.clear()
    fun enterScreen() = undoState.resume()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    val gasAuto = repo.isGasPriceAuto.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    private var finishing = false

    private fun <T> sharedWrite(key: String, label: Int, action: suspend (SaveCapture<T>) -> T,
        restore: suspend (T, () -> Boolean) -> Boolean): Deferred<WriteResult> {
        if (_busy.value && !finishing) return CompletableDeferred(WriteResult.REJECTED)
        val capture = SaveCapture<T>()
        return undoState.write {
            val previous = action(capture)
            LastSaveUndo.checked(key, label, previous, restore)
        }
    }

    fun setTimeConstantOverride(field: EconomyField, value: Double?) = sharedWrite(field.name,
        if (field == EconomyField.AVG_MIN_PER_MILE) R.string.economy_time_pace else R.string.economy_time_overhead,
        { repo.setTimeConstantOverride(field, value, it) },
        { old, current -> repo.restoreTimeConstantOverride(field, old, StoredPreference(value, value != null), current) })
    fun setElectricityPrice(value: Double) = sharedWrite("ELECTRICITY_PRICE", R.string.vehicle_facts_electricity_price,
        { repo.updateElectricityPrice(value, it) }, { old, current -> repo.restoreElectricityPrice(old, StoredPreference(value, true), current) })
    fun setGasPrice(value: Double) = sharedWrite("GAS_PRICE", R.string.economy_shared_gas_price,
        { repo.updateGasPriceManual(value.toFloat(), it) },
        { old, current -> repo.restoreGasPriceManual(old, StoredGasPrice(StoredPreference(value.toFloat(), old.price.userSet), StoredPreference(false, false)), current) })
    fun setGasAuto(value: Boolean) = sharedWrite("GAS_AUTO", R.string.economy_shared_auto_gas_price,
        { repo.setGasPriceAuto(value, it) }, { old, current -> repo.restoreGasPriceAuto(old, StoredPreference(value, false), current) })
    fun setPhoneTotal(value: Double) = sharedWrite("PHONE_PLAN_TOTAL", R.string.economy_editor_phone_plan_total_label,
        { repo.updatePhoneTotal(value, it) }, { old, current -> repo.restorePhoneTotal(old, StoredPreference(value, true), current) })
    fun setPhoneLines(value: Int) = sharedWrite("PHONE_PLAN_LINES", R.string.economy_editor_phone_lines_label,
        { repo.updatePhoneLines(value, it) }, { old, current -> repo.restorePhoneLines(old, StoredPreference(value, true), current) })
    fun setPhoneShare(value: Double) = sharedWrite("PHONE_BUSINESS_PERCENT", R.string.economy_undo_phone_share,
        { repo.updatePhoneShare(value, it) }, { old, current -> repo.restorePhoneShare(old, StoredPreference(value, true), current) })
    fun retry() { writer.retry() }
    fun resumeEditing() { undoState.resume(); _busy.value = false; edits.acceptingEdits = true }
    suspend fun discardChanges() {
        clearUndo()
        edits.discardAll()
        writer.discard()
    }
    suspend fun awaitIdle() = writer.awaitIdle()
    suspend fun finishAndAwait(): GarageWriteFailure = withContext(NonCancellable) {
        clearUndo()
        edits.acceptingEdits = false
        _busy.value = true
        finishing = true
        val submissions = try { edits.submitAll() } finally { finishing = false }
        val finished = submissions.map { it.await() }.all { it == WriteResult.SAVED }
        val result = writer.awaitIdle()
        if (!finished && result == GarageWriteFailure.NONE) GarageWriteFailure.RETRY else result
    }
    suspend fun resetDefaults(): GarageWriteFailure = withContext(NonCancellable) {
        clearUndo()
        _busy.value = true
        edits.acceptingEdits = false
        edits.discardAll()
        val reset = writer.write { repo.resetSharedEconomyDefaults(); true }
        val result = writer.awaitIdle()
        resumeEditing()
        if (reset.await() != WriteResult.SAVED && result == GarageWriteFailure.NONE) GarageWriteFailure.RETRY else result
    }
}
