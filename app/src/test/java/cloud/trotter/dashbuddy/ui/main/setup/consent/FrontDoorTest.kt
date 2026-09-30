package cloud.trotter.dashbuddy.ui.main.setup.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 review LL1/LL6/MM6 — the front door shows at most ONE prompt (capabilities first, the event
 * receipt only once capabilities are ANSWERED), a "Not now" closes the whole door until the app really
 * leaves the foreground, and re-composition never lapses it.
 */
class FrontDoorTest {

    private val fresh = FrontDoorDeferrals()

    @Test
    fun `never two prompts - capabilities first, event receipt once capabilities are answered`() {
        assertEquals(FrontDoorPrompt.CAPABILITIES, pickFrontDoorPrompt(true, true, fresh))
        // The capability rows are all decided ⇒ the SAME door advances to the event receipt.
        assertEquals(FrontDoorPrompt.EVENT_RECEIPT, pickFrontDoorPrompt(false, true, fresh))
        assertEquals(FrontDoorPrompt.CAPABILITIES, pickFrontDoorPrompt(true, false, fresh))
        assertNull(pickFrontDoorPrompt(false, false, fresh))
    }

    @Test
    fun `a deferral closes the whole door - it never hands off to the next prompt`() {
        val d = fresh.defer()
        assertNull(pickFrontDoorPrompt(true, true, d))
        assertNull(pickFrontDoorPrompt(false, true, d))
        assertNull(pickFrontDoorPrompt(true, false, d))
    }

    @Test
    fun `a deferral holds within the foreground and lapses on the next one`() {
        val d = fresh.defer()
        // Re-reading the same state (rotation, navigation back, re-composition) changes nothing.
        assertTrue(d.isDeferred)
        assertNull(pickFrontDoorPrompt(false, true, d))

        val next = d.onBackgrounded()
        assertFalse(next.isDeferred)
        assertEquals(FrontDoorPrompt.CAPABILITIES, pickFrontDoorPrompt(true, true, next))
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
