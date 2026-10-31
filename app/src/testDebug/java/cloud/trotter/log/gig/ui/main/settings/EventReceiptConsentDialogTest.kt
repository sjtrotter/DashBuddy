package cloud.trotter.log.gig.ui.main.settings

import androidx.activity.ComponentDialog
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cloud.trotter.log.gig.core.designsystem.theme.GigLogTheme
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.settings.EventReceiptConsent
import cloud.trotter.log.gig.domain.settings.EventReceiptPreferences
import cloud.trotter.log.gig.feature.settings.CapabilityConsentScreen
import cloud.trotter.log.gig.feature.settings.CapabilityConsentViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * #1457 — the Screen events switch on Automation & consent. Turning it on shows the disclosure
 * first; only Allow or Don't allow writes (each through `EventReceiptPreferences.set`, which writes
 * the receipt in the same edit); Back writes nothing; a failed write closes the dialog and the
 * switch shows the stored value. This runs in the debug variant, where turning it off asks first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EventReceiptConsentDialogTest {
    @get:Rule val compose = createComposeRule()

    private class FakeEventReceipt(initial: EventReceiptConsent, private val writeSucceeds: Boolean = true) :
        EventReceiptPreferences {
        private val value = MutableStateFlow<EventReceiptConsent?>(initial)
        override val consent: StateFlow<EventReceiptConsent?> = value
        override val receipt = MutableStateFlow<ConsentReceipt?>(null)
        val writes = mutableListOf<EventReceiptConsent>()
        override suspend fun set(consent: EventReceiptConsent): Boolean {
            writes += consent
            if (writeSucceeds) value.value = consent
            return writeSucceeds
        }
    }

    private class NoCapabilities : RuleCapabilityGrants {
        override val grantedKeys = MutableStateFlow<Set<String>>(emptySet())
        override val deniedKeys = MutableStateFlow<Set<String>>(emptySet())
        override val receipts = MutableStateFlow<Map<String, ConsentReceipt>>(emptyMap())
        override val capabilities = MutableStateFlow<List<RuleCapability>>(emptyList())
        override suspend fun setGranted(key: String, granted: Boolean) = error("unused")
        override suspend fun reconcile(capabilities: List<RuleCapability>) = error("unused")
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction) = error("unused")
    }

    private fun show(prefs: FakeEventReceipt) {
        val vm = CapabilityConsentViewModel(NoCapabilities(), prefs)
        compose.setContent { GigLogTheme { CapabilityConsentScreen(onBack = {}, viewModel = vm) } }
    }

    private fun screenEventsSwitch() = compose.onNode(isToggleable())
    private fun disclosureBody() = compose.onNodeWithText("gig.log gets a signal from every app", substring = true)

    @Test
    fun `turning it on shows the disclosure, and Allow writes ALLOWED`() {
        val prefs = FakeEventReceipt(EventReceiptConsent.UNDECIDED)
        show(prefs)
        screenEventsSwitch().assertIsOff().performClick()
        disclosureBody().assertIsDisplayed()
        assertTrue("opening the dialog writes nothing", prefs.writes.isEmpty())

        compose.onNodeWithText("Allow").performClick()

        assertEquals(listOf(EventReceiptConsent.ALLOWED), prefs.writes)
        disclosureBody().assertDoesNotExist()
        screenEventsSwitch().assertIsOn()
    }

    @Test
    fun `Don't allow writes a lasting DECLINED`() {
        val prefs = FakeEventReceipt(EventReceiptConsent.UNDECIDED)
        show(prefs)
        screenEventsSwitch().performClick()

        compose.onNodeWithText("Don't allow").performClick()

        assertEquals(listOf(EventReceiptConsent.DECLINED), prefs.writes)
        disclosureBody().assertDoesNotExist()
        screenEventsSwitch().assertIsOff()
    }

    @Test
    fun `Back closes the dialog and writes nothing`() {
        val prefs = FakeEventReceipt(EventReceiptConsent.DECLINED)
        show(prefs)
        screenEventsSwitch().performClick()
        disclosureBody().assertIsDisplayed()

        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertTrue(prefs.writes.isEmpty())
        disclosureBody().assertDoesNotExist()
        screenEventsSwitch().assertIsOff()
    }

    @Test
    fun `a failed write closes the dialog and the switch reads back the stored value`() {
        val prefs = FakeEventReceipt(EventReceiptConsent.UNDECIDED, writeSucceeds = false)
        show(prefs)
        screenEventsSwitch().performClick()

        compose.onNodeWithText("Allow").performClick()

        assertEquals(listOf(EventReceiptConsent.ALLOWED), prefs.writes)
        disclosureBody().assertDoesNotExist()
        screenEventsSwitch().assertIsOff()
    }

    @Test
    fun `the Android 11 caveat is not shown on later Android versions`() {
        show(FakeEventReceipt(EventReceiptConsent.UNDECIDED))
        screenEventsSwitch().performClick()
        disclosureBody().assertIsDisplayed()
        compose.onNodeWithText(ANDROID_11_CAVEAT).assertDoesNotExist()
    }

    @Test
    @Config(sdk = [30])
    fun `on Android 11 the dialog carries the caveat`() {
        show(FakeEventReceipt(EventReceiptConsent.UNDECIDED))
        screenEventsSwitch().performClick()
        compose.onNodeWithText(ANDROID_11_CAVEAT).assertIsDisplayed()
    }

    @Test
    fun `in a test build, turning it off asks first`() {
        val prefs = FakeEventReceipt(EventReceiptConsent.ALLOWED)
        show(prefs)
        screenEventsSwitch().assertIsOn().performClick()
        compose.onNodeWithText("Turn off Screen events?").assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        assertTrue("Cancel writes nothing", prefs.writes.isEmpty())
        screenEventsSwitch().assertIsOn()

        screenEventsSwitch().performClick()
        compose.onNodeWithText("Turn off").performClick()
        assertEquals(listOf(EventReceiptConsent.DECLINED), prefs.writes)
        screenEventsSwitch().assertIsOff()
    }

    private companion object {
        const val ANDROID_11_CAVEAT = "On Android 11, this may not take effect."
    }
}
