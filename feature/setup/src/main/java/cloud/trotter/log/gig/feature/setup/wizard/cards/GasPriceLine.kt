package cloud.trotter.log.gig.feature.setup.wizard.cards

import cloud.trotter.log.gig.feature.setup.wizard.model.GasPriceSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The line under the setup card's gas price (#1478): where the figure came from, or that the lookup failed.
 * "Regional average" is only ever said of a figure a lookup returned, with the day it did.
 */
sealed interface GasPriceLine {
    data object Today : GasPriceLine
    data class FetchedOn(val date: LocalDate) : GasPriceLine
    data class FailedUsing(val price: Float, val date: LocalDate) : GasPriceLine
    data object FailedNoLookup : GasPriceLine
    data object Typed : GasPriceLine
    data object Saved : GasPriceLine

    val offersRetry: Boolean get() = this is FailedUsing || this is FailedNoLookup

    companion object {
        /** [nowMillis] is the composition clock's reading, so a card left open past midnight relabels itself. */
        fun of(source: GasPriceSource, lookupFailed: Boolean, price: Float, nowMillis: Long, zone: ZoneId): GasPriceLine {
            val fetchedOn = (source as? GasPriceSource.Lookup)?.let { day(it.fetchedAtMillis, zone) }
            return when {
                lookupFailed -> if (fetchedOn != null) FailedUsing(price, fetchedOn) else FailedNoLookup
                fetchedOn != null -> if (fetchedOn == day(nowMillis, zone)) Today else FetchedOn(fetchedOn)
                source == GasPriceSource.Saved -> Saved
                else -> Typed
            }
        }

        private fun day(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    }
}
