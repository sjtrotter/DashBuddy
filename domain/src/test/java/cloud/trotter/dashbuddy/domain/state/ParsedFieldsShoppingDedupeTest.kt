package cloud.trotter.dashbuddy.domain.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for the Shop & Deliver items/min off-by-one (field log 2026-06-05).
 *
 * The post-classification dedup suppresses an observation whose identity equals
 * the previously-emitted one, and that identity hashes [ParsedFields.dedupeHash].
 * For a shopping task the item counts ARE the progress identity, so they must be
 * part of the hash — otherwise the decisive "To shop (0)" / Done(total) frame is
 * collapsed against the prior frame and dropped, capping itemsShopped at total-1.
 */
class ParsedFieldsShoppingDedupeTest {

    private fun shopping(remaining: Int?, shopped: Int?) = ParsedFields.TaskFields(
        activity = PickupActivity.SHOPPING,
        phase = TaskPhase.PICKUP,
        subFlow = TaskSubFlow.ARRIVED,
        storeName = "H-E-B",
        itemsRemaining = remaining,
        itemsShopped = shopped,
    )

    @Test
    fun `shopping frames with different item counts have different dedupe hashes`() {
        assertNotEquals(
            "remaining 1 and remaining 0 must be distinct identities, or the terminal frame is deduped away",
            shopping(remaining = 1, shopped = 14).dedupeHash(),
            shopping(remaining = 0, shopped = 15).dedupeHash(),
        )
    }

    @Test
    fun `each step of shopping progress is a distinct identity`() {
        val hashes = (0..5).map { shopped ->
            shopping(remaining = 5 - shopped, shopped = shopped).dedupeHash()
        }
        assertEquals("all six progress states must be unique", 6, hashes.toSet().size)
    }

    @Test
    fun `identical shopping frames still dedupe (same hash)`() {
        assertEquals(
            shopping(remaining = 3, shopped = 12).dedupeHash(),
            shopping(remaining = 3, shopped = 12).dedupeHash(),
        )
    }

    @Test
    fun `non-shopping tasks with null counts are unaffected`() {
        val a = ParsedFields.TaskFields(
            phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, storeName = "H-E-B",
        )
        val b = ParsedFields.TaskFields(
            phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, storeName = "H-E-B",
        )
        assertEquals(a.dedupeHash(), b.dedupeHash())
    }
}
