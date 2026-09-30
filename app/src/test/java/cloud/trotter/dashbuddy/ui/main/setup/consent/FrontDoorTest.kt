package cloud.trotter.dashbuddy.ui.main.setup.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 review LL1/LL6/MM6/OO2 — the front door shows at most ONE prompt (capabilities first, the
 * event receipt only once capabilities are ANSWERED), nothing until both sources are ready, a
 * "Not now" closes the whole door until the app really leaves the foreground, and re-composition
 * never lapses it.
 */
class FrontDoorTest {

    private val fresh = FrontDoorDeferrals()

    /** Both sources ready — the common case. */
    private fun pick(caps: Boolean, receipt: Boolean, d: FrontDoorDeferrals = fresh) =
        pickFrontDoorPrompt(true, caps, true, receipt, d)

    @Test
    fun `never two prompts - capabilities first, event receipt once capabilities are answered`() {
        assertEquals(FrontDoorPrompt.CAPABILITIES, pick(caps = true, receipt = true))
        // The capability rows are all decided ⇒ the SAME door advances to the event receipt.
        assertEquals(FrontDoorPrompt.EVENT_RECEIPT, pick(caps = false, receipt = true))
        assertEquals(FrontDoorPrompt.CAPABILITIES, pick(caps = true, receipt = false))
        assertNull(pick(caps = false, receipt = false))
    }

    @Test
    fun `nothing until both sources are ready - a delayed capability publication never lets the receipt go first`() {
        // Cold launch: receipt read (UNDECIDED), capabilities NOT yet published (empty rows).
        assertNull(pickFrontDoorPrompt(false, false, true, true, fresh))
        // Receipt not read yet, capabilities published.
        assertNull(pickFrontDoorPrompt(true, true, false, false, fresh))
        // The rule load publishes one undecided capability: the FIRST prompt is CAPABILITIES.
        assertEquals(FrontDoorPrompt.CAPABILITIES, pickFrontDoorPrompt(true, true, true, true, fresh))
    }

    @Test
    fun `a deferral closes the whole door - it never hands off to the next prompt`() {
        val d = fresh.defer()
        assertNull(pick(caps = true, receipt = true, d = d))
        assertNull(pick(caps = false, receipt = true, d = d))
        assertNull(pick(caps = true, receipt = false, d = d))
    }

    @Test
    fun `a deferral holds within the foreground and lapses on the next one`() {
        val d = fresh.defer()
        // Re-reading the same state (rotation, navigation back, re-composition) changes nothing.
        assertTrue(d.isDeferred)
        assertNull(pick(caps = false, receipt = true, d = d))

        val next = d.onBackgrounded()
        assertFalse(next.isDeferred)
        assertEquals(FrontDoorPrompt.CAPABILITIES, pick(caps = true, receipt = true, d = next))
    }

    @Test
    fun `the view model applies the same transitions`() {
        val vm = FrontDoorViewModel()
        vm.defer()
        assertTrue(vm.deferrals.value.isDeferred)
        vm.onBackgrounded()
        assertFalse(vm.deferrals.value.isDeferred)
    }
}
