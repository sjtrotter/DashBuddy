package cloud.trotter.dashbuddy.ui.main.settings.garage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.trotter.dashbuddy.core.data.vehicle.Garage
import cloud.trotter.dashbuddy.core.data.vehicle.GarageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class GarageViewModel @Inject constructor(
    private val garageRepository: GarageRepository,
    private val clock: Clock,
) : ViewModel() {
    val garage: StateFlow<Garage?> = garageRepository.garage.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null,
    )
    private val writer = GarageWriter(viewModelScope, "Garage")
    val busy = writer.busy
    val failure = writer.failure

    fun select(id: String) = writer.write { garageRepository.selectVehicle(id) }
    fun retire(id: String) = writer.write { garageRepository.retire(id, clock.millis()) }
}
