package cloud.trotter.log.gig.feature.setup.wizard.model

/**
 * Where the wizard's gas price figure came from (#1478). State holds this anchor; the card derives its line from it
 * and the composition clock, so "today" is decided at render time.
 */
sealed interface GasPriceSource {
    /** A lookup returned the figure at [fetchedAtMillis]. */
    data class Lookup(val fetchedAtMillis: Long) : GasPriceSource

    /** The dasher typed the figure, or auto is off. */
    data object Typed : GasPriceSource

    /** Auto is on and no fetch time is stored (a price saved before the stamp existed). */
    data object Saved : GasPriceSource

    companion object {
        /** The source of a stored price: auto off marks a typed one, the stamp a looked-up one. */
        fun stored(isAuto: Boolean, fetchedAtMillis: Long?): GasPriceSource = when {
            !isAuto -> Typed
            fetchedAtMillis != null -> Lookup(fetchedAtMillis)
            else -> Saved
        }
    }
}

/** One fuel's looked-up price and when the lookup returned it. */
data class FetchedGasPrice(val price: Float, val fetchedAtMillis: Long)
