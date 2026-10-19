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
    private val addingVehicleState: MutableState<Boolean> = mutableStateOf(false),
) {
    var open by openState
    var page by pageState
    private var digits by digitsState
    private var addVehicleFrom by addVehicleFromState
    private var addingVehicle by addingVehicleState
    val reading: Int? get() = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).value
    fun digit(digit: Int) { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).digit(digit).digits }
    fun backspace() { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).backspace().digits }
    fun show(hasVehicle: Boolean) {
        if (open) return
        addVehicleFrom = OdometerPage.VEHICLES
        addingVehicle = false
        digits = ""
        page = if (hasVehicle) OdometerPage.READING else OdometerPage.ADD_VEHICLE
        open = true
    }
    fun showAddVehicle() {
        addingVehicle = true
        if (page != OdometerPage.ADD_VEHICLE) addVehicleFrom = page
        page = OdometerPage.ADD_VEHICLE
    }
    fun vehicleChanged() {
        if (!addingVehicle) digits = ""
        addingVehicle = false
        page = OdometerPage.READING
    }
    fun back(hasVehicle: Boolean) {
        if (page == OdometerPage.ADD_VEHICLE) addingVehicle = false
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
    val addingVehicle = rememberSaveable { mutableStateOf(false) }
    return remember { OdometerEntryState(open, page, digits, addVehicleFrom, addingVehicle) }
}
