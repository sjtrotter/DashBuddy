package cloud.trotter.dashbuddy.ui.components

import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCell
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #979 — the Patterns tab leaderboard sort orders, the wait-outlier predicate, and the heatmap
 * Rate/Hours toggle's cell-value selection. Compose-free, per the [WindowLabel] precedent: the
 * interesting part is sort/threshold reasoning, and it should be provable without rendering.
 */
class PatternsModelTest {

    private fun card(
        storeKey: String,
        chainDisplay: String = storeKey,
        net: Double = 0.0,
        p50DwellMillis: Long? = null,
        lastSeenAt: Long = 0L,
        deliveries: Int = 1,
    ) = StoreReportCard(
        storeKey = storeKey,
        platform = "doordash",
        normalizedChain = chainDisplay,
        chainDisplay = chainDisplay,
        runningKey = "1",
        address = null,
        locationKnown = true,
        pickups = deliveries,
        deliveries = deliveries,
        gross = net,
        net = net,
        avgDwellMillis = p50DwellMillis?.toDouble(),
        p50DwellMillis = p50DwellMillis,
        p95DwellMillis = p50DwellMillis,
        firstSeenAt = 0L,
        lastSeenAt = lastSeenAt,
    )

    // ── sortedStores ────────────────────────────────────────────────────

    @Test fun `NET sorts highest net first`() {
        val cards = listOf(card("a", net = 10.0), card("b", net = 30.0), card("c", net = 20.0))
        val sorted = PatternsModel.sortedStores(cards, PatternsModel.LeaderboardSort.NET)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.storeKey })
    }

    @Test fun `NET breaks ties by name for determinism`() {
        val cards = listOf(card("z", chainDisplay = "Zebra", net = 10.0), card("a", chainDisplay = "Apple", net = 10.0))
        val sorted = PatternsModel.sortedStores(cards, PatternsModel.LeaderboardSort.NET)
        assertEquals(listOf("Apple", "Zebra"), sorted.map { it.chainDisplay })
    }

    @Test fun `WAIT sorts shortest usual wait first`() {
        val cards = listOf(
            card("slow", p50DwellMillis = 900_000L),
            card("fast", p50DwellMillis = 60_000L),
            card("mid", p50DwellMillis = 300_000L),
        )
        val sorted = PatternsModel.sortedStores(cards, PatternsModel.LeaderboardSort.WAIT)
        assertEquals(listOf("fast", "mid", "slow"), sorted.map { it.storeKey })
    }

    @Test fun `WAIT sorts a store with no wait sample to the bottom`() {
        val cards = listOf(
            card("unsampled", p50DwellMillis = null),
            card("sampled", p50DwellMillis = 60_000L),
        )
        val sorted = PatternsModel.sortedStores(cards, PatternsModel.LeaderboardSort.WAIT)
        assertEquals(listOf("sampled", "unsampled"), sorted.map { it.storeKey })
    }

    @Test fun `RECENT sorts most recently seen first`() {
        val cards = listOf(card("old", lastSeenAt = 100L), card("new", lastSeenAt = 300L), card("mid", lastSeenAt = 200L))
        val sorted = PatternsModel.sortedStores(cards, PatternsModel.LeaderboardSort.RECENT)
        assertEquals(listOf("new", "mid", "old"), sorted.map { it.storeKey })
    }

    // ── maxNet / netBarFraction ─────────────────────────────────────────

    @Test fun `maxNet is the highest net across the list`() {
        val cards = listOf(card("a", net = 10.0), card("b", net = 55.0), card("c", net = -20.0))
        assertEquals(55.0, PatternsModel.maxNet(cards), 0.0)
    }

    @Test fun `maxNet of an empty list is zero`() {
        assertEquals(0.0, PatternsModel.maxNet(emptyList()), 0.0)
    }

    @Test fun `the number-1 store's own bar fraction is 1`() {
        assertEquals(1f, PatternsModel.netBarFraction(net = 55.0, maxNet = 55.0), 0.0001f)
    }

    @Test fun `a store at half the leader's net renders a half bar`() {
        assertEquals(0.5f, PatternsModel.netBarFraction(net = 25.0, maxNet = 50.0), 0.0001f)
    }

    @Test fun `negative net renders an empty bar, never a negative fraction`() {
        assertEquals(0f, PatternsModel.netBarFraction(net = -10.0, maxNet = 50.0), 0.0001f)
    }

    @Test fun `a non-positive maxNet renders an empty bar for every store`() {
        assertEquals(0f, PatternsModel.netBarFraction(net = 5.0, maxNet = 0.0), 0.0001f)
    }

    // ── isWaitOutlier ───────────────────────────────────────────────────

    @Test fun `a store far above the fleet median wait is flagged an outlier`() {
        val cards = listOf(
            card("a", p50DwellMillis = 300_000L), // 5 min
            card("b", p50DwellMillis = 320_000L),
            card("slow", p50DwellMillis = 900_000L), // 15 min — 3x the ~5min median
        )
        val slow = cards.first { it.storeKey == "slow" }
        assertTrue(PatternsModel.isWaitOutlier(cards, slow))
    }

    @Test fun `a store near the fleet median is not an outlier`() {
        val cards = listOf(
            card("a", p50DwellMillis = 300_000L),
            card("b", p50DwellMillis = 320_000L),
            card("c", p50DwellMillis = 310_000L),
        )
        val c = cards.first { it.storeKey == "c" }
        assertFalse(PatternsModel.isWaitOutlier(cards, c))
    }

    @Test fun `below the minimum fleet sample, nothing is flagged an outlier`() {
        // Only 2 stores carry a wait sample — below MIN_FLEET_SAMPLE_FOR_OUTLIER (3), so even a huge
        // gap must not be called out (thin-data honesty, §9).
        val cards = listOf(card("a", p50DwellMillis = 60_000L), card("slow", p50DwellMillis = 3_600_000L))
        val slow = cards.first { it.storeKey == "slow" }
        assertFalse(PatternsModel.isWaitOutlier(cards, slow))
    }

    @Test fun `a store with no wait sample of its own is never an outlier`() {
        val cards = listOf(
            card("a", p50DwellMillis = 60_000L),
            card("b", p50DwellMillis = 65_000L),
            card("none", p50DwellMillis = null),
        )
        val none = cards.first { it.storeKey == "none" }
        assertFalse(PatternsModel.isWaitOutlier(cards, none))
    }

    @Test fun `exactly at the multiplier threshold counts as an outlier`() {
        // fleet median with an odd sample count 300_000/300_000/300_000 -> median 300_000; 1.5x = 450_000.
        val cards = listOf(
            card("a", p50DwellMillis = 300_000L),
            card("b", p50DwellMillis = 300_000L),
            card("edge", p50DwellMillis = 450_000L),
        )
        val edge = cards.first { it.storeKey == "edge" }
        assertTrue(PatternsModel.isWaitOutlier(cards, edge))
    }

    // ── heatmap Rate/Hours toggle ───────────────────────────────────────

    private fun cell(dollarsPerHour: Double?, coverageHours: Double) =
        EarningsHeatmapCell(dayIndex = 0, hour = 0, netDollars = 0.0, coverageHours = coverageHours, dollarsPerHour = dollarsPerHour)

    @Test fun `RATE mode reads dollarsPerHour verbatim, including a masked null`() {
        assertEquals(4.5, PatternsModel.cellValue(cell(4.5, 2.0), PatternsModel.HeatmapMode.RATE))
        assertEquals(null, PatternsModel.cellValue(cell(null, 0.1), PatternsModel.HeatmapMode.RATE))
    }

    @Test fun `HOURS mode reads coverageHours, folding a genuine zero to null`() {
        assertEquals(2.0, PatternsModel.cellValue(cell(null, 2.0), PatternsModel.HeatmapMode.HOURS))
        assertEquals(null, PatternsModel.cellValue(cell(5.0, 0.0), PatternsModel.HeatmapMode.HOURS))
    }

    @Test fun `maxCoverageHours is the largest cell coverage in the grid`() {
        val heatmap = EarningsHeatmap(
            cells = listOf(cell(null, 1.0), cell(2.0, 3.5), cell(1.0, 0.2)),
            minCoverageHours = EarningsHeatmap.EMPTY.minCoverageHours,
        )
        assertEquals(3.5, PatternsModel.maxCoverageHours(heatmap), 0.0)
    }

    @Test fun `maxCoverageHours of an empty grid is zero`() {
        assertEquals(0.0, PatternsModel.maxCoverageHours(EarningsHeatmap(emptyList(), 0.5)), 0.0)
    }
}
