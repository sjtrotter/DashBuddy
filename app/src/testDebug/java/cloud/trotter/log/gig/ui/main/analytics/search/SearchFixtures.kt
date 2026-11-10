package cloud.trotter.log.gig.ui.main.analytics.search

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.search.ShiftMatches
import cloud.trotter.log.gig.domain.analytics.search.StoreQuery
import cloud.trotter.log.gig.domain.analytics.search.TimelineSearch
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.ui.main.analytics.ShiftPageFixtures
import java.time.Instant

/**
 * The Search screen's results for the Compose tests and the screenshots: two shifts on Tuesday 2026-10-27 (UTC), newest
 * first. The DoorDash one is [ShiftPageFixtures.stackAndAddOn] (its Five Guys & Chipotle stack); the Uber one, earlier
 * that day, declined a Chipotle offer. Store names are merchants; no customer data.
 */
internal object SearchFixtures {
    const val DOORDASH_SHIFT = "shift-1"
    const val UBER_SHIFT = "shift-uber"

    /** The stack's card key, and the Uber shift's declined offer. */
    const val STACK_KEY = ShiftPageFixtures.STACK_KEY
    const val UBER_OFFER_KEY = "offer:501"

    private val doordash = DayShift(
        sessionId = DOORDASH_SHIFT, platform = Platform.DoorDash,
        startedAt = Instant.parse("2026-10-27T16:58:00Z").toEpochMilli(),
        endedAt = Instant.parse("2026-10-27T23:04:00Z").toEpochMilli(),
        earned = 25.50, profit = 14.0, paySeen = true,
    )
    private val uber = DayShift(
        sessionId = UBER_SHIFT, platform = Platform.Uber,
        startedAt = Instant.parse("2026-10-27T11:10:00Z").toEpochMilli(),
        endedAt = Instant.parse("2026-10-27T13:40:00Z").toEpochMilli(),
        earned = 0.0, profit = 0.0, paySeen = false,
    )

    /** The answer to [query] over both shifts, as the repository folds it. */
    fun results(query: String = "chipotle"): SearchUiState {
        val search = StoreQuery.of(query)!!
        val stack = ShiftPageFixtures.stackAndAddOn()
        val uberOffers = listOf(
            TimelineOffer(
                501, "h501", OfferOutcome.DECLINED, uber.startedAt + 40 * 60_000L, 5.25, 2.2, "Chipotle",
                OfferScope.STANDALONE, OfferQuoteBasis.TOTAL, linkedJobId = null,
            ),
        )
        val uberItems = ShiftTimeline.fold(uberOffers, emptyList(), emptyList(), running = false)
        val groups = listOf(
            ShiftMatches(doordash, TimelineSearch.filter(stack.timeline, stack.offers, stack.deliveries, search)),
            ShiftMatches(uber, TimelineSearch.filter(uberItems, uberOffers, emptyList(), search)),
        ).filter { it.rows.isNotEmpty() }
        return SearchUiState(resultsFor = search.normalized, groups = groups)
    }

    /** The answer for a store no shift has. */
    fun noMatch(query: String = "Wingstop"): SearchUiState = SearchUiState(resultsFor = StoreQuery.normalize(query))
}
