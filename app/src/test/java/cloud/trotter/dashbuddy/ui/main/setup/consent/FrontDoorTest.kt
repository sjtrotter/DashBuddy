package cloud.trotter.dashbuddy.ui.main.setup.consent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 review LL1/LL3/LL6 — the front door shows at most ONE prompt (capabilities first), and a
 * "Not now" holds until the app really leaves the foreground, never merely until re-composition.
 */
class FrontDoorTest {

    private val fresh = FrontDoorDeferrals()

    @Test
    fun `never two prompts - capabilities first, event receipt only when capabilities is not showing`() {
        assertEquals(FrontDoorPrompt.CAPABILITIES, pickFrontDoorPrompt(true, true, fresh))
        assertEquals(FrontDoorPrompt.EVENT_RECEIPT, pickFrontDoorPrompt(false, true, fresh))
        assertEquals(FrontDoorPrompt.CAPABILITIES, pickFrontDoorPrompt(true, false, fresh))
        assertNull(pickFrontDoorPrompt(false, false, fresh))
    }

    @Test
    fun `deferring capabilities hands the door to the event-receipt prompt`() {
        val d = fresh.defer(FrontDoorPrompt.CAPABILITIES)
        assertEquals(FrontDoorPrompt.EVENT_RECEIPT, pickFrontDoorPrompt(true, true, d))
        assertNull(pickFrontDoorPrompt(true, true, d.defer(FrontDoorPrompt.EVENT_RECEIPT)))
    }

    @Test
    fun `a deferral holds within the foreground and lapses on the next one`() {
        val d = fresh.defer(FrontDoorPrompt.EVENT_RECEIPT)
        // Re-reading the same state (rotation, navigation back, re-composition) changes nothing.
        assertTrue(d.isDeferred(FrontDoorPrompt.EVENT_RECEIPT))
        assertNull(pickFrontDoorPrompt(false, true, d))

        val next = d.onBackgrounded()
        assertFalse(next.isDeferred(FrontDoorPrompt.EVENT_RECEIPT))
        assertEquals(FrontDoorPrompt.EVENT_RECEIPT, pickFrontDoorPrompt(false, true, next))
    }

    @Test
    fun `the view model applies the same transitions`() {
        val vm = FrontDoorViewModel()
        vm.defer(FrontDoorPrompt.CAPABILITIES)
        assertTrue(vm.deferrals.value.isDeferred(FrontDoorPrompt.CAPABILITIES))
        vm.onBackgrounded()
        assertFalse(vm.deferrals.value.isDeferred(FrontDoorPrompt.CAPABILITIES))
    }
}
