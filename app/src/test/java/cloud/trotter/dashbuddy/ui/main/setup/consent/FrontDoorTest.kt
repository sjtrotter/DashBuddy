package cloud.trotter.dashbuddy.ui.main.setup.consent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1151 review LL6/MM6 — the capability prompt shows while rows are pending, a "Not now" closes the
 * door until the app really leaves the foreground, and re-composition never lapses it.
 */
class FrontDoorTest {

    private val fresh = FrontDoorDeferrals()

    @Test
    fun `the prompt shows only while capabilities are undecided`() {
        assertTrue(showCapabilityPrompt(rowsPending = true, deferrals = fresh))
        assertFalse(showCapabilityPrompt(rowsPending = false, deferrals = fresh))
    }

    @Test
    fun `a deferral holds within the foreground and lapses on the next one`() {
        val d = fresh.defer()
        // Re-reading the same state (rotation, navigation back, re-composition) changes nothing.
        assertTrue(d.isDeferred)
        assertFalse(showCapabilityPrompt(rowsPending = true, deferrals = d))

        val next = d.onBackgrounded()
        assertFalse(next.isDeferred)
        assertTrue(showCapabilityPrompt(rowsPending = true, deferrals = next))
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
