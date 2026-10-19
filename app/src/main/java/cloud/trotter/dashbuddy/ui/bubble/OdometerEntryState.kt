package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.dashbuddy.domain.model.vehicle.OdometerReadingLimit
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.WholeNumberEntry
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerPage

@Stable
internal class OdometerEntryState(
    private val openState: MutableState<Boolean>,
    private val pageState: MutableState<OdometerPage>,
    private val digitsState: MutableState<String>,
    private val addVehicleFromState: MutableState<OdometerPage> = mutableStateOf(OdometerPage.VEHICLES),
) {
    var open by openState
    var page by pageState
    private var digits by digitsState
    private var addVehicleFrom by addVehicleFromState
    val reading: Int? get() = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).value
    fun digit(digit: Int) { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).digit(digit).digits }
    fun backspace() { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).backspace().digits }
    fun show(hasVehicle: Boolean) {
        if (open) return
        addVehicleFrom = OdometerPage.VEHICLES
        digits = ""
        page = if (hasVehicle) OdometerPage.READING else OdometerPage.ADD_VEHICLE
        open = true
    }
    fun showAddVehicle() {
        if (page != OdometerPage.ADD_VEHICLE) addVehicleFrom = page
        page = OdometerPage.ADD_VEHICLE
    }
    fun vehicleChanged() { page = OdometerPage.READING }
    fun back(hasVehicle: Boolean) {
        when {
            !hasVehicle || page == OdometerPage.READING -> open = false
            page == OdometerPage.ADD_VEHICLE -> page = addVehicleFrom
            else -> page = OdometerPage.READING
        }
    }
}

@Composable
internal fun rememberOdometerEntryState(): OdometerEntryState {
    val open = rememberSaveable { mutableStateOf(false) }
    val page = rememberSaveable { mutableStateOf(OdometerPage.READING) }
    val digits = rememberSaveable { mutableStateOf("") }
    val addVehicleFrom = rememberSaveable { mutableStateOf(OdometerPage.VEHICLES) }
    return remember { OdometerEntryState(open, page, digits, addVehicleFrom) }
}
