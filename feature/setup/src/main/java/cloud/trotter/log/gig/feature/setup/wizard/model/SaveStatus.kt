package cloud.trotter.log.gig.feature.setup.wizard.model

/**
 * Where the wizard's save stands (#1473). The completion sheet renders an outcome from this, so nothing says
 * saved before the write has finished.
 */
sealed interface SaveStatus {
    data object Idle : SaveStatus

    /** A save is running; a second one cannot start. */
    data object Saving : SaveStatus

    /** Every write finished. */
    data object Saved : SaveStatus

    /** A write threw; the wizard's answers are untouched, so a Retry runs the same save again. */
    data class Failed(val reason: SaveFailure) : SaveStatus
}

enum class SaveFailure {
    /** The vehicle write failed; the targets and costs were still written. */
    VEHICLE,

    /** A settings or offer-rule write threw. */
    SETTINGS,
}
