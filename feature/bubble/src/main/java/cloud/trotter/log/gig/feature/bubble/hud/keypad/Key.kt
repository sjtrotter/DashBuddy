package cloud.trotter.log.gig.feature.bubble.hud.keypad

sealed interface Key {
    data class Digit(val n: Int) : Key
    data object Point : Key
    data object Backspace : Key
}
