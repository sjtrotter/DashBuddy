package cloud.trotter.dashbuddy.domain.state

import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #528 Slice A — per-drop realized-pay attribution. The apportioner splits ONE combined
 * delivery receipt into per-drop shares (exact per-store tip + equal-split base) so a stacked
 * job's DELIVERY_COMPLETED rows each carry their own share instead of one drop absorbing the
 * whole receipt and the others being null.
 *
 * The load-bearing property is the **no-double-count invariant**: the shares sum EXACTLY to the
 * receipt total (`ParsedPay.total`) in integer cents, with the rounding remainder given to the
 * last drop.
 */
class DropPayApportionerTest {

    private fun cents(v: Double): Long = Math.round(v * 100.0)

    private fun drop(id: String, store: String?, cust: String = "cust-$id") = Task(
        taskId = id,
        jobId = "job-1",
        phase = TaskPhase.DROPOFF,
        storeName = store,
        customerNameHash = cust,
        startedAt = 300L,
        completedAt = 400L,
    )

    /** The invariant, asserted in integer cents. */
    private fun assertSumsToReceipt(shares: Map<String, Double>, receipt: ParsedPay) {
        val summed = shares.values.sumOf { cents(it) }
        assertEquals(
            "sum of dropRealizedPay must equal the receipt total (no double count)",
            cents(receipt.total),
            summed,
        )
    }

    @Test
    fun `two-store stack — exact tip plus equal-split base, sums to receipt`() {
        // base $8 lump, tips Target $6 + Maple $2 → total $16.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 8.0)),
            customerTips = listOf(
                ParsedPayItem("Target (02426)", 6.0),
                ParsedPayItem("Maple Street Biscuit - Alamo Ranch", 2.0),
            ),
        )
        val drops = listOf(
            drop("d-target", "Target"),
            drop("d-maple", "Maple Street Biscuit Company"),
        )

        val shares = DropPayApportioner.apportion(receipt, drops)

        // Target: exact tip $6 + base $8/2 = $10. Maple: $2 + $4 = $6.
        assertEquals(10.0, shares.getValue("d-target"), 0.001)
        assertEquals(6.0, shares.getValue("d-maple"), 0.001)
        assertSumsToReceipt(shares, receipt)
    }

    @Test
    fun `same-store two-drop batch — falls back to equal split, never double counts`() {
        // GoPuff-style: one store, two customers, two same-labelled tip lines. The tips can't be
        // injectively assigned (both drops match both lines) → equal-split fallback. Proves the
        // naive same-store double-count can't happen.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 8.0)),
            customerTips = listOf(
                ParsedPayItem("GoPuff", 3.0),
                ParsedPayItem("GoPuff", 5.0),
            ),
        )
        val drops = listOf(
            drop("d1", "GoPuff"),
            drop("d2", "GoPuff"),
        )

        val shares = DropPayApportioner.apportion(receipt, drops)

        // Equal split of $16 across 2 drops = $8 each. Neither drop absorbs both tip lines.
        assertEquals(8.0, shares.getValue("d1"), 0.001)
        assertEquals(8.0, shares.getValue("d2"), 0.001)
        assertSumsToReceipt(shares, receipt)
    }

    @Test
    fun `blank dropoff store name — equal-split fallback`() {
        // Real per #526: dropoff cards often have no storeName parse. Can't tip-match → fallback.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 5.0)),
            customerTips = listOf(
                ParsedPayItem("Chipotle", 4.0),
                ParsedPayItem("Wendy's", 1.0),
            ),
        )
        val drops = listOf(
            drop("d1", "Chipotle"),
            drop("d2", null),
        )

        val shares = DropPayApportioner.apportion(receipt, drops)
        assertSumsToReceipt(shares, receipt)
    }

    @Test
    fun `receipt-less drop — no attribution (null parsedPay yields empty map)`() {
        val shares = DropPayApportioner.apportion(
            parsedPay = null,
            dropoffTasks = listOf(drop("d1", "H-E-B")),
        )
        assertTrue("no receipt → nothing to attribute", shares.isEmpty())
    }

    @Test
    fun `single-drop job — the whole receipt total`() {
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.5)),
            customerTips = listOf(ParsedPayItem("Wendy's", 3.0)),
        )
        val drops = listOf(drop("d1", "Wendy's"))

        val shares = DropPayApportioner.apportion(receipt, drops)
        assertEquals(7.5, shares.getValue("d1"), 0.001)
        assertSumsToReceipt(shares, receipt)
    }

    @Test
    fun `odd-cent split gives the rounding remainder to the last drop, still exact`() {
        // total $10.01 across 3 equal-split drops = 3.336.. → 334 + 334 + remainder(333) = 1001c.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 10.01)),
            customerTips = listOf(
                ParsedPayItem("A store", 0.0),
                ParsedPayItem("A store", 0.0),
                ParsedPayItem("A store", 0.0),
            ),
        )
        val drops = listOf(
            drop("d1", "A store"),
            drop("d2", "A store"),
            drop("d3", "A store"),
        )
        val shares = DropPayApportioner.apportion(receipt, drops)
        assertSumsToReceipt(shares, receipt)
    }

    // ── #630 finding 4: order invariance (canonical taskId sort inside the apportioner) ──

    /** All permutations of a list (small n only — test helper). */
    private fun <T> permutations(items: List<T>): List<List<T>> {
        if (items.size <= 1) return listOf(items)
        return items.flatMap { head ->
            permutations(items - head).map { rest -> listOf(head) + rest }
        }
    }

    @Test
    fun `apportion — identical map (incl the remainder cent) for every input-order permutation`() {
        // Equal-split path with a rounding remainder: $10.01 across 3 drops.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 10.01)),
            customerTips = emptyList(),
        )
        val drops = listOf(drop("d1", null), drop("d2", null), drop("d3", null))

        val canonical = DropPayApportioner.apportion(receipt, drops)
        permutations(drops).forEach { perm ->
            assertEquals(
                "apportion must be input-order invariant (a resume between a stack's two mint " +
                    "steps can reorder the denominator, #630 finding 4)",
                canonical,
                DropPayApportioner.apportion(receipt, perm),
            )
        }
        assertSumsToReceipt(canonical, receipt)
    }

    @Test
    fun `apportion — two half-cent shares pin the extra cent to the FIRST taskId in canonical order`() {
        // $10.01 across 2 drops = $5.005 each. toCents rounds half-up → the first drop in canonical
        // (taskId-sorted) order takes 501c, the LAST takes the remainder 500c — regardless of the
        // caller's input order.
        val receipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 10.01)),
            customerTips = emptyList(),
        )
        val forward = DropPayApportioner.apportion(receipt, listOf(drop("d-a", null), drop("d-b", null)))
        val reversed = DropPayApportioner.apportion(receipt, listOf(drop("d-b", null), drop("d-a", null)))

        assertEquals(5.01, forward.getValue("d-a"), 0.0001)
        assertEquals(5.00, forward.getValue("d-b"), 0.0001)
        assertEquals("reversed input order yields the identical assignment", forward, reversed)
        assertSumsToReceipt(forward, receipt)
    }

    @Test
    fun `equalSplit — identical map for every input-order permutation`() {
        val drops = listOf(drop("d1", null), drop("d2", null), drop("d3", null))
        val canonical = DropPayApportioner.equalSplit(10.01, drops)
        permutations(drops).forEach { perm ->
            assertEquals(
                "equalSplit must be input-order invariant (#630 finding 4)",
                canonical,
                DropPayApportioner.equalSplit(10.01, perm),
            )
        }
        assertEquals(1001L, canonical.values.sumOf { cents(it) })
    }

    // ── #691 equalSplit: the offer-pay fallback for a wholly receipt-less job ──

    @Test
    fun `equalSplit — two drops, cents-exact remainder to last`() {
        // Offer total $12.95 over 2 drops: 6.475 → toCents rounds first to 648, remainder 647 last.
        val drops = listOf(drop("d1", "H-E-B"), drop("d2", "H-E-B"))
        val shares = DropPayApportioner.equalSplit(12.95, drops)
        assertEquals(6.48, shares.getValue("d1"), 0.0001)
        assertEquals(6.47, shares.getValue("d2"), 0.0001)
        assertEquals("shares sum EXACTLY to the offer total", 1295L, shares.values.sumOf { cents(it) })
    }

    @Test
    fun `equalSplit — single drop takes the whole offer total`() {
        val shares = DropPayApportioner.equalSplit(26.50, listOf(drop("d1", "Target")))
        assertEquals(26.50, shares.getValue("d1"), 0.0001)
    }

    @Test
    fun `equalSplit — empty denominator yields empty map`() {
        assertTrue(DropPayApportioner.equalSplit(12.95, emptyList()).isEmpty())
    }

    @Test
    fun `equalSplit — null total yields empty map`() {
        assertTrue(DropPayApportioner.equalSplit(null, listOf(drop("d1", "H-E-B"))).isEmpty())
    }

    @Test
    fun `equalSplit — zero total yields empty map`() {
        assertTrue(DropPayApportioner.equalSplit(0.0, listOf(drop("d1", "H-E-B"))).isEmpty())
    }

    @Test
    fun `equalSplit — negative total yields empty map`() {
        assertTrue(DropPayApportioner.equalSplit(-5.0, listOf(drop("d1", "H-E-B"))).isEmpty())
    }

    @Test
    fun `equalSplit — dedupes drops by taskId`() {
        // A duplicated task id must not inflate the denominator.
        val drops = listOf(drop("d1", "H-E-B"), drop("d1", "H-E-B"), drop("d2", "H-E-B"))
        val shares = DropPayApportioner.equalSplit(9.00, drops)
        assertEquals(2, shares.size)
        assertEquals(900L, shares.values.sumOf { cents(it) })
    }
}
