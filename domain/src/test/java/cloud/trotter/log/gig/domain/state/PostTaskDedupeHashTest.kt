package cloud.trotter.log.gig.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The receipt's running total is part of its observation identity (#1029).
 *
 * `FrameGate.admit` drops a frame whose `Observation.identity()` equals the last admitted one, and
 * that identity hashes [ParsedFields.dedupeHash]. On the DoorDash 8.93.7 receipt the ONLY field
 * that moves while the sheet sits still is `sessionEarnings` — it is rendered by the same animated
 * digit-wheel as the on-dash pill. Without it in the hash, the frame that catches the wheel MID-SPIN
 * and the re-render one second later where it has SETTLED are the same identity, the settled one is
 * dropped, and the receipt path can never correct itself. This PR's own golden had approved a
 * pre-roll `sessionEarnings` of 17.75 for a receipt whose expanded capture read 35.47 a second later.
 */
class PostTaskDedupeHashTest {

    private fun receipt(sessionEarnings: Double?) = ParsedFields.PostTaskFields(
        totalPay = 16.70,
        appPay = null,
        customerTips = 7.00,
        sessionEarnings = sessionEarnings,
    )

    @Test
    fun `a settled wheel is a DIFFERENT identity from the mid-spin frame before it`() {
        assertNotEquals(
            "a re-render whose running total moved must be admitted, or the receipt cannot self-correct",
            receipt(17.75).dedupeHash(),
            receipt(35.47).dedupeHash(),
        )
    }

    @Test
    fun `an unparsed running total is distinct from a parsed one`() {
        assertNotEquals(receipt(null).dedupeHash(), receipt(16.70).dedupeHash())
    }

    @Test
    fun `identical receipts still dedupe`() {
        assertEquals(receipt(16.70).dedupeHash(), receipt(16.70).dedupeHash())
        assertEquals(receipt(null).dedupeHash(), receipt(null).dedupeHash())
    }

    @Test
    fun `the pay fields still drive identity on their own`() {
        val a = ParsedFields.PostTaskFields(totalPay = 16.70, customerTips = 7.00)
        val b = ParsedFields.PostTaskFields(totalPay = 16.70, customerTips = 8.00)
        assertNotEquals(a.dedupeHash(), b.dedupeHash())
    }

    // #1598: a receipt whose itemization changes while its totals hold, or that expands, is new evidence.
    private val itemizedA = cloud.trotter.log.gig.domain.model.pay.ParsedPay(
        appPayComponents = listOf(cloud.trotter.log.gig.domain.model.pay.ParsedPayItem("Base Pay", 4.0)),
        customerTips = listOf(cloud.trotter.log.gig.domain.model.pay.ParsedPayItem("Store A", 6.0)),
    )
    private val itemizedB = cloud.trotter.log.gig.domain.model.pay.ParsedPay(
        appPayComponents = listOf(cloud.trotter.log.gig.domain.model.pay.ParsedPayItem("Base Pay", 5.0)),
        customerTips = listOf(cloud.trotter.log.gig.domain.model.pay.ParsedPayItem("Store A", 5.0)),
    )

    @Test
    fun `#1598 equal totals with a different itemization are a different identity`() {
        val a = ParsedFields.PostTaskFields(totalPay = 10.0, parsedPay = itemizedA, isExpanded = true)
        val b = ParsedFields.PostTaskFields(totalPay = 10.0, parsedPay = itemizedB, isExpanded = true)
        assertNotEquals(a.dedupeHash(), b.dedupeHash())
    }

    @Test
    fun `#1598 a collapsed receipt and its expansion are a different identity`() {
        val collapsed = ParsedFields.PostTaskFields(totalPay = 10.0, isExpanded = false)
        val expanded = ParsedFields.PostTaskFields(totalPay = 10.0, isExpanded = true)
        assertNotEquals(collapsed.dedupeHash(), expanded.dedupeHash())
        val itemized = ParsedFields.PostTaskFields(totalPay = 10.0, parsedPay = itemizedA, isExpanded = true)
        assertEquals(itemized.dedupeHash(), itemized.copy().dedupeHash())
    }
}
