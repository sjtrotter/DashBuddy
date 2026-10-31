package cloud.trotter.log.gig.ui.main.setup.permissions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent
import cloud.trotter.log.gig.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1457 — the sheet's close rule: it hides only once nothing is pending, and after the hide it calls
 * `onClosed` only if that still holds. A queue that refills while the sheet is sliding out brings it
 * back with the new step and reports nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionsBottomSheetTest {
    @get:Rule val compose = createComposeRule()

    private class AllowedPrefs : EventReceiptPreferences {
        override val consent: StateFlow<EventReceiptConsent?> = MutableStateFlow(EventReceiptConsent.ALLOWED)
        override val receipt = MutableStateFlow<ConsentReceipt?>(null)
        override suspend fun set(consent: EventReceiptConsent): Boolean = error("unused")
    }

    private val allGranted = OsPermissionPoll(true, true, true, true, true)
    private var os = allGranted.copy(bubbles = false)
    private var closed = 0

    private fun show(): PermissionsViewModel {
        val vm = PermissionsViewModel({ os }, AllowedPrefs(), isDebugBuild = false, PermissionDeferrals())
        compose.setContent {
            GigLogTheme { PermissionsBottomSheet(onClosed = { closed++ }, viewModel = vm) }
        }
        compose.waitForIdle()
        return vm
    }

    /** Re-poll the OS and let one frame run, so the sheet's keyed effect restarts on the new queue. */
    private fun repoll(vm: PermissionsViewModel, poll: OsPermissionPoll) {
        os = poll
        compose.runOnUiThread { vm.refresh() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a queue that refills during the hide brings the sheet back and reports nothing`() {
        val vm = show()
        compose.onNodeWithText("Bubble").assertIsDisplayed()
        compose.onNodeWithText("Step 6 of 6").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        repoll(vm, allGranted) // the last grant: the hide starts
        compose.mainClock.advanceTimeByFrame()
        repoll(vm, allGranted.copy(postNotifications = false)) // revoked mid-hide

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        assertEquals("a refilled gate does not report closed", 0, closed)
        compose.onNodeWithText("Notifications").assertIsDisplayed()
        compose.onNodeWithText("Step 5 of 6").assertIsDisplayed()

        os = allGranted
        compose.runOnUiThread { vm.refresh() }
        compose.waitForIdle()
        assertEquals("the gate reports closed once, after the hide", 1, closed)
    }

    @Test
    fun `Not now on the last pending step closes the sheet`() {
        show()
        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        assertEquals(1, closed)
    }
}
