package cloud.trotter.dashbuddy.domain.state

/**
 * The worker's connection / availability posture on a given platform.
 *
 * Mode is orthogonal to [Flow]. A worker can be `Paused` while their
 * flow is `Idle`, or `Online` while in `TaskPickupNavigation`.
 *
 * Per ADR-0002 amendment, mode is **inferred** by the state machine
 * from flow observations and occasional mode hints — rules declare
 * `modeHint`, not authoritative mode.
 */
enum class Mode(val wire: String) {
    Offline("offline"),
    Online("online"),
    Paused("paused"),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(wire: String): Mode? = byWire[wire]
    }
}
