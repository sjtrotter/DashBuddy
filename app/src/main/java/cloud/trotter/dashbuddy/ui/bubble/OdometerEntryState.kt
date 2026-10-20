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
    private val switcherFromRowState: MutableState<Boolean> = mutableStateOf(false),
) {
    var open by openState
    var page by pageState
    private var digits by digitsState
    var switcherFromRow by switcherFromRowState
        private set
    val reading: Int? get() = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).value
    fun digit(digit: Int) { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).digit(digit).digits }
    fun backspace() { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).backspace().digits }
    fun show(activeVehicle: Boolean, anyVehicles: Boolean): Boolean {
        if (open || !anyVehicles) return false
        switcherFromRow = false
        digits = ""
        page = if (activeVehicle) OdometerPage.READING else OdometerPage.VEHICLES
        open = true
        return true
    }
    fun showVehicles(): Boolean {
        if (open) return false
        digits = ""
        switcherFromRow = true
        page = OdometerPage.VEHICLES
        open = true
        return true
    }
    fun vehicleSwitched() {
        digits = ""
        if (switcherFromRow) open = false else page = OdometerPage.READING
    }
    /** Retirement keeps the entered digits, including when the last car is removed. */
    fun vehicleRetired(wasActive: Boolean, activeVehicle: Boolean, anyVehicles: Boolean) {
        if (!wasActive && anyVehicles) return
        if (!anyVehicles) {
            open = false
            return
        }
        page = if (activeVehicle) OdometerPage.READING else OdometerPage.VEHICLES
    }
    fun back(hasVehicle: Boolean) {
        when {
            page == OdometerPage.VEHICLES && switcherFromRow -> open = false
            !hasVehicle || page == OdometerPage.READING -> open = false
            else -> page = OdometerPage.READING
        }
    }
}

@Composable
internal fun rememberOdometerEntryState(): OdometerEntryState {
    val open = rememberSaveable { mutableStateOf(false) }
    val page = rememberSaveable { mutableStateOf(OdometerPage.READING) }
    val digits = rememberSaveable { mutableStateOf("") }
    val switcherFromRow = rememberSaveable { mutableStateOf(false) }
    return remember { OdometerEntryState(open, page, digits, switcherFromRow) }
}
