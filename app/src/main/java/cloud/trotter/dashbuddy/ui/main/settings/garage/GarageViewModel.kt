package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

enum class GarageWriteFailure { NONE, RETRY, UNREADABLE }

@HiltViewModel
class GarageViewModel @Inject constructor(
    private val garageRepository: GarageRepository,
    private val clock: Clock,
) : ViewModel() {
    val garage: StateFlow<Garage?> = garageRepository.garage.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null,
    )
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failure = MutableStateFlow(GarageWriteFailure.NONE)
    val failure = _failure.asStateFlow()

    fun select(id: String) = write { garageRepository.selectVehicle(id) }
    fun retire(id: String) = write { garageRepository.retire(id, clock.millis()) }

    private fun write(action: suspend () -> Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val wrote = withContext(NonCancellable) { action() }
                // A stale row's refused write leaves the last completed outcome untouched.
                if (wrote) _failure.value = GarageWriteFailure.NONE
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _failure.value = if (error is VehiclesUnreadableException) GarageWriteFailure.UNREADABLE else GarageWriteFailure.RETRY
                Timber.tag("Garage").w("%s", error.javaClass.simpleName)
            } finally {
                _busy.value = false
            }
        }
    }
}
