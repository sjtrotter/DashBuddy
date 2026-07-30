package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferCandidate
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #973 / brief §4.2 — the "Needs a look" card's gating. The four stacked amber callouts became rows of
 * one card, and the value of this test is proving that was a **layout** change only: every flag fires
 * on exactly the condition its standalone callout fired on. Compose-free, per the [MoneyWentModel]
 * precedent (the copy is a separate concern; only the decision is tested here).
 */
class ReviewFlagsTest {

    private fun economics(
        unattributed: Double = 0.0,
        overAttributed: Double = 0.0,
        noSessionDeliveries: Int = 0,
        noSessionPay: Double = 0.0,
    ) = PeriodEconomics(
        totals = PeriodTotals(deliveries = 4, onlineDuration = 3_600_000L),
        grossEarnings = 100.0,
        netProfit = 70.0,
        unattributedPay = unattributed,
        netPerHour = null,
        netPerMile = null,
        overAttributedPay = overAttributed,
        noSessionPay = noSessionPay,
        noSessionDeliveries = noSessionDeliveries,
    )

    private fun orphanGroup(owed: Int, resolved: Int = 0, offers: Int = 2) = OrphanOfferGroup(
        jobId = "job-1",
        sessionId = "dash-1",
        orphansOwed = owed,
        offers = List(offers) { index ->
            OrphanOfferCandidate(
                offerEventSequenceId = index.toLong(),
                storeName = "Store $index",
                payAmount = 8.50,
                decidedAt = 1_000L + index,
                resolved = index < resolved,
            )
        },
    )

    @Test
    fun `a clean window raises nothing — the card renders not at all`() {
        assertEquals(emptyList<ReviewFlags.Flag>(), ReviewFlags.of(economics(), emptyList()))
    }

    @Test
    fun `each signal raises its own flag`() {
        assertEquals(
            listOf(ReviewFlags.Flag.UNATTRIBUTED),
            ReviewFlags.of(economics(unattributed = 12.40), emptyList()),
        )
        assertEquals(
            listOf(ReviewFlags.Flag.OVER_ATTRIBUTED),
            ReviewFlags.of(economics(overAttributed = 3.10), emptyList()),
        )
        assertEquals(
            listOf(ReviewFlags.Flag.NO_SESSION),
            ReviewFlags.of(economics(noSessionDeliveries = 2, noSessionPay = 18.0), emptyList()),
        )
        assertEquals(
            listOf(ReviewFlags.Flag.ORPHAN_OFFERS),
            ReviewFlags.of(economics(), listOf(orphanGroup(owed = 1))),
        )
    }

    @Test
    fun `flags list in a fixed order regardless of magnitude`() {
        val flags = ReviewFlags.of(
            economics(unattributed = 1.0, overAttributed = 90.0, noSessionDeliveries = 1, noSessionPay = 5.0),
            listOf(orphanGroup(owed = 2)),
        )

        assertEquals(
            listOf(
                ReviewFlags.Flag.UNATTRIBUTED,
                ReviewFlags.Flag.OVER_ATTRIBUTED,
                ReviewFlags.Flag.NO_SESSION,
                ReviewFlags.Flag.ORPHAN_OFFERS,
            ),
            flags,
        )
    }

    @Test
    fun `a sub-cent money figure is not a review item`() {
        assertEquals(
            emptyList<ReviewFlags.Flag>(),
            ReviewFlags.of(economics(unattributed = 0.001, overAttributed = 0.001), emptyList()),
        )
    }

    @Test
    fun `a pay-less orphan delivery still raises the no-session flag`() {
        // Gated on the COUNT, not the pay (#660): a $0.00 orphan is still a categorizable item.
        assertEquals(
            listOf(ReviewFlags.Flag.NO_SESSION),
            ReviewFlags.of(economics(noSessionDeliveries = 1, noSessionPay = 0.0), emptyList()),
        )
    }

    @Test
    fun `a fully-resolved orphan group no longer raises the flag`() {
        // #810 B2 F3: the group stays listed in the dialog so the attestation stays undoable, but its
        // owedRemaining is 0, so it must not keep nagging from the card.
        assertEquals(
            emptyList<ReviewFlags.Flag>(),
            ReviewFlags.of(economics(), listOf(orphanGroup(owed = 1, resolved = 1))),
        )
    }

    @Test
    fun `owed counts sum across groups`() {
        assertEquals(
            listOf(ReviewFlags.Flag.ORPHAN_OFFERS),
            ReviewFlags.of(economics(), listOf(orphanGroup(owed = 1, resolved = 1), orphanGroup(owed = 1))),
        )
    }
}
