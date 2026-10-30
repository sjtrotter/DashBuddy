package cloud.trotter.log.gig.domain.state

/**
 * Rule-declared: this idle screen structurally means the dasher holds no order
 * (DoorDash's Finding-offers screen). Declared under `state.idleSurface` and load-validated.
 */
enum class IdleSurface(val wire: String) {
    NO_ORDERS("no_orders"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(wire: String): IdleSurface? = byWire[wire]
    }
}
