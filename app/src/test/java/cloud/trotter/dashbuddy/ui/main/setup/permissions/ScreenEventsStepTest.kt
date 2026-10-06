package cloud.trotter.dashbuddy.ui.main.setup.permissions

import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1151 (dev re-sequencing, 2026-09-30) — the event-receipt consent is the permission chain's FIRST
 * step: it leads the queue while UNDECIDED, the accessibility grant is offered only after a decision,
 * a release decline proceeds to the grant, and a DEBUG decline shows the notice IN PLACE of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreenEventsStepTest {

    private val nothingGranted = OsPermissionPoll()
    private val allGranted = OsPermissionPoll(true, true, true, true, true)

    @Test
    fun `UNDECIDED - the Screen-events step leads and the accessibility step is withheld`() {
        for (debug in listOf(true, false)) {
            val s = buildPermissionsUiState(nothingGranted, EventReceiptConsent.UNDECIDED, debug)
            assertEquals(PermissionStep.ScreenEvents, s.steps.first())
            assertFalse(PermissionType.Accessibility in s.missing)
        }
    }

    @Test
    fun `an already-enabled service still gets the Screen-events step first`() {
        val s = buildPermissionsUiState(allGranted, EventReceiptConsent.UNDECIDED, isDebugBuild = false)
        assertEquals(listOf<PermissionStep>(PermissionStep.ScreenEvents), s.steps)
        assertFalse(s.allGranted)
    }

    @Test
    fun `a decision hands the chain to the accessibility grant (release - either answer)`() {
        for (consent in listOf(EventReceiptConsent.ALLOWED, EventReceiptConsent.DECLINED)) {
            val s = buildPermissionsUiState(nothingGranted, consent, isDebugBuild = false)
            assertEquals(PermissionStep.Os(PermissionType.Accessibility), s.steps.first())
            assertFalse(s.screenEventsDue)
        }
    }

    @Test
    fun `debug ALLOWED proceeds to the grant`() {
        val s = buildPermissionsUiState(nothingGranted, EventReceiptConsent.ALLOWED, isDebugBuild = true)
        assertEquals(PermissionStep.Os(PermissionType.Accessibility), s.steps.first())
    }

    @Test
    fun `debug DECLINED withholds the accessibility grant`() {
        val s = buildPermissionsUiState(nothingGranted, EventReceiptConsent.DECLINED, isDebugBuild = true)
        assertFalse(PermissionType.Accessibility in s.missing)
        assertFalse(accessibilityOffered(EventReceiptConsent.DECLINED, isDebugBuild = true))
    }

    @Test
    fun `before the consent is read the queue is EMPTY and nothing is all-granted (TT4)`() {
        for (debug in listOf(true, false)) {
            val s = buildPermissionsUiState(nothingGranted, null, isDebugBuild = debug)
            assertTrue("no OS card may lead before the read", s.steps.isEmpty())
            assertFalse(s.allGranted)
            assertFalse(PermissionType.Accessibility in s.missing)
        }
    }

    @Test
    fun `once read, Screen-events heads the queue before every OS card (TT4)`() {
        val s = buildPermissionsUiState(nothingGranted, EventReceiptConsent.UNDECIDED, isDebugBuild = false)
        assertEquals(PermissionStep.ScreenEvents, s.steps.first())
        assertTrue(s.steps.drop(1).all { it is PermissionStep.Os })
        assertEquals(5, s.steps.size) // Screen-events + the four non-accessibility OS cards
    }

    // ---- ViewModel ------------------------------------------------------------------------

    private class FakePrefs(initial: EventReceiptConsent?) : EventReceiptPreferences {
        override val receipt = MutableStateFlow<ConsentReceipt?>(null)
        val flow = MutableStateFlow(initial)
        override val consent: StateFlow<EventReceiptConsent?> = flow
        val writes = mutableListOf<EventReceiptConsent>()
        override suspend fun set(consent: EventReceiptConsent): Boolean {
            writes += consent
            flow.value = consent
            return true
        }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the step's decision writes through the one owner and the queue moves on`() = runTest {
        val prefs = FakePrefs(EventReceiptConsent.UNDECIDED)
        val vm = PermissionsViewModel({ nothingGranted }, prefs, isDebugBuild = false)
        assertEquals(PermissionStep.ScreenEvents, vm.uiState.value.steps.first())

        vm.onScreenEventsDecision(allow = true)

        assertEquals(listOf(EventReceiptConsent.ALLOWED), prefs.writes)
        assertEquals(PermissionStep.Os(PermissionType.Accessibility), vm.uiState.value.steps.first())
    }

    @Test
    fun `refresh re-polls the OS`() = runTest {
        var poll = nothingGranted
        val vm = PermissionsViewModel({ poll }, FakePrefs(EventReceiptConsent.ALLOWED), isDebugBuild = false)
        assertFalse(vm.uiState.value.allGranted)
        poll = allGranted
        vm.refresh()
        assertTrue(vm.uiState.value.allGranted)
    }
}
