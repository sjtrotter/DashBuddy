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
}
