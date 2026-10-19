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
    private val vehicleNameState: MutableState<String> = mutableStateOf(""),
    private val switcherFromRowState: MutableState<Boolean> = mutableStateOf(false),
) {
    var open by openState
    var page by pageState
    private var digits by digitsState
    private var addVehicleFrom by addVehicleFromState
    var vehicleName by vehicleNameState
    var switcherFromRow by switcherFromRowState
        private set
    val reading: Int? get() = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).value
    fun digit(digit: Int) { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).digit(digit).digits }
    fun backspace() { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).backspace().digits }
    fun show(activeVehicle: Boolean, anyVehicles: Boolean): Boolean {
        if (open) return false
        switcherFromRow = false
        addVehicleFrom = OdometerPage.VEHICLES
        digits = ""
        vehicleName = ""
        page = when {
            activeVehicle -> OdometerPage.READING
            anyVehicles -> OdometerPage.VEHICLES
            else -> OdometerPage.ADD_VEHICLE
        }
        open = true
        return true
    }
    fun showVehicles(): Boolean {
        if (open) return false
        addVehicleFrom = OdometerPage.VEHICLES
        digits = ""
        vehicleName = ""
        switcherFromRow = true
        page = OdometerPage.VEHICLES
        open = true
        return true
    }
    fun showAddVehicle() {
        if (page != OdometerPage.ADD_VEHICLE) addVehicleFrom = page
        page = OdometerPage.ADD_VEHICLE
    }
    fun vehicleAdded() {
        if (addVehicleFrom != OdometerPage.READING) digits = ""
        vehicleName = ""
        page = OdometerPage.READING
    }
    fun vehicleSwitched() {
        digits = ""
        if (switcherFromRow) open = false else page = OdometerPage.READING
    }
    /** Retirement keeps the entered digits, including when the last car is removed. */
    fun vehicleRetired(wasActive: Boolean, activeVehicle: Boolean, anyVehicles: Boolean) {
        if (!wasActive && anyVehicles) return
        page = when {
            activeVehicle -> OdometerPage.READING
            anyVehicles -> OdometerPage.VEHICLES
            else -> OdometerPage.ADD_VEHICLE
        }
        addVehicleFrom = page
    }
    fun back(hasVehicle: Boolean) {
        when {
            page == OdometerPage.VEHICLES && switcherFromRow -> open = false
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
    val vehicleName = rememberSaveable { mutableStateOf("") }
    val switcherFromRow = rememberSaveable { mutableStateOf(false) }
    return remember { OdometerEntryState(open, page, digits, addVehicleFrom, vehicleName, switcherFromRow) }
}
