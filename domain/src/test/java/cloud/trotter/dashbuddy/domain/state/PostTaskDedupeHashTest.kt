package cloud.trotter.dashbuddy.domain.state

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
}
