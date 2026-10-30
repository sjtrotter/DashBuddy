package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.trotter.log.gig.domain.model.vehicle.OdometerReadingLimit
import cloud.trotter.log.gig.feature.bubble.hud.keypad.WholeNumberEntry

@Stable
internal class OdometerEntryState(
    private val openState: MutableState<Boolean>,
    private val digitsState: MutableState<String>,
) {
    var open by openState
    private var digits by digitsState
    val reading: Int? get() = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).value
    fun digit(digit: Int) { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).digit(digit).digits }
    fun backspace() { digits = WholeNumberEntry(digits, OdometerReadingLimit.DIGITS).backspace().digits }
    fun show(anyVehicles: Boolean): Boolean {
        if (open || !anyVehicles) return false
        digits = ""
        open = true
        return true
    }
    fun vehicleSwitched() { digits = "" }
    fun back() { open = false }
}

@Composable
internal fun rememberOdometerEntryState(): OdometerEntryState {
    val open = rememberSaveable { mutableStateOf(false) }
    val digits = rememberSaveable { mutableStateOf("") }
    return remember { OdometerEntryState(open, digits) }
}
