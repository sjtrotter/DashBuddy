package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.WholeNumberEntry
import cloud.trotter.dashbuddy.feature.bubble.hud.odometer.OdometerPage

@Stable
internal class OdometerEntryState(
    private val openState: MutableState<Boolean>,
    private val pageState: MutableState<OdometerPage>,
    private val digitsState: MutableState<String>,
) {
    var open by openState
    var page by pageState
    private var digits by digitsState
    val reading: Int? get() = WholeNumberEntry(digits).value
    fun digit(digit: Int) { digits = WholeNumberEntry(digits).digit(digit).digits }
    fun backspace() { digits = WholeNumberEntry(digits).backspace().digits }
    fun show(hasVehicle: Boolean) {
        digits = ""
        page = if (hasVehicle) OdometerPage.READING else OdometerPage.ADD_VEHICLE
        open = true
    }
    fun vehicleChanged() { digits = ""; page = OdometerPage.READING }
    fun back(hasVehicle: Boolean) {
        when {
            !hasVehicle || page == OdometerPage.READING -> open = false
            page == OdometerPage.ADD_VEHICLE -> page = OdometerPage.VEHICLES
            else -> page = OdometerPage.READING
        }
    }
}

@Composable
internal fun rememberOdometerEntryState(): OdometerEntryState {
    val open = rememberSaveable { mutableStateOf(false) }
    val page = rememberSaveable { mutableStateOf(OdometerPage.READING) }
    val digits = rememberSaveable { mutableStateOf("") }
    return remember { OdometerEntryState(open, page, digits) }
}
