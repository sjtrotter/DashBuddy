package cloud.trotter.dashbuddy.feature.bubble.hud.keypad

/** Whole-number buffer shared by entry sheets; no currency or persistence policy. */
data class WholeNumberEntry(val digits: String = "") {
    init { require(digits.length <= 6 && digits.all { it in '0'..'9' } && !digits.startsWith('0')) }
    val value: Int? get() = digits.toIntOrNull()
    fun digit(digit: Int): WholeNumberEntry {
        require(digit in 0..9)
        return if (digits.length == 6 || (digits.isEmpty() && digit == 0)) this
        else WholeNumberEntry(digits + digit)
    }
    fun backspace() = WholeNumberEntry(digits.dropLast(1))
}
