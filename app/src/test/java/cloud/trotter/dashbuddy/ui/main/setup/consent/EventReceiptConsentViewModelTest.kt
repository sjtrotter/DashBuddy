package cloud.trotter.dashbuddy.ui.main.setup.consent

import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
 * #1151 — the wide-event-receipt consent surface. The pure projection decides both the prompt
 * (UNDECIDED, and only once the store was read) and the debug block (DEBUG && DECLINED — never in a
 * release build, never for UNDECIDED/ALLOWED); the ViewModel's only write goes through
 * [EventReceiptPreferences]. There is no Compose UI test infrastructure in `:app`, so the block's
 * reachability is pinned here at the `blocked` flag the Dashboard renders on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventReceiptConsentViewModelTest {

    private class FakePrefs(initial: EventReceiptConsent? = EventReceiptConsent.UNDECIDED) :
        EventReceiptPreferences {
        val consentFlow = MutableStateFlow(initial)
        override val consent: StateFlow<EventReceiptConsent?> = consentFlow
        val setCalls = mutableListOf<EventReceiptConsent>()
        override suspend fun set(consent: EventReceiptConsent): Boolean {
            setCalls += consent
            consentFlow.value = consent
            return true
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- pure projection ------------------------------------------------------------------

    @Test
    fun `UNDECIDED shows the prompt`() {
        for (debug in listOf(true, false)) {
            val s = buildEventReceiptConsentState(EventReceiptConsent.UNDECIDED, isDebugBuild = debug)
            assertTrue(s.showPrompt)
            assertFalse(s.blocked)
        }
    }

    @Test
    fun `the prompt never flashes before the store is read`() {
        for (debug in listOf(true, false)) {
            assertFalse(buildEventReceiptConsentState(null, debug).showPrompt)
        }
    }

    @Test
    fun `before the read a debug shell is loading - not blocked, not navigable - and release is untouched`() {
        val debug = buildEventReceiptConsentState(null, isDebugBuild = true)
        assertTrue(debug.loading)
        assertFalse(debug.blocked)
        assertFalse(debug.navigable)

        assertEquals(EventReceiptConsentUiState(), buildEventReceiptConsentState(null, isDebugBuild = false))
        assertTrue(buildEventReceiptConsentState(null, isDebugBuild = false).navigable)
    }

    @Test
    fun `the loading gate offers Exit only after the delay`() {
        assertFalse(loadingGateShowsExit(0L))
        assertFalse(loadingGateShowsExit(LOADING_EXIT_AFTER_MS - 1))
        assertTrue(loadingGateShowsExit(LOADING_EXIT_AFTER_MS))
    }

    @Test
    fun `a known value is never loading`() {
        for (consent in EventReceiptConsent.entries) {
            for (debug in listOf(true, false)) {
                val s = buildEventReceiptConsentState(consent, debug)
                assertFalse(s.loading)
                assertEquals(!s.blocked, s.navigable)
            }
        }
    }

    @Test
    fun `ALLOWED and DECLINED hide the prompt`() {
        for (consent in listOf(EventReceiptConsent.ALLOWED, EventReceiptConsent.DECLINED)) {
            for (debug in listOf(true, false)) {
                assertFalse(buildEventReceiptConsentState(consent, isDebugBuild = debug).showPrompt)
            }
        }
    }

    @Test
    fun `the block is reachable only when DEBUG and DECLINED`() {
        for (consent in EventReceiptConsent.entries + listOf(null)) {
            for (debug in listOf(true, false)) {
                assertEquals(
                    "consent=$consent debug=$debug",
                    debug && consent == EventReceiptConsent.DECLINED,
                    buildEventReceiptConsentState(consent, debug).blocked,
                )
            }
        }
    }

    // ---- ViewModel ------------------------------------------------------------------------

    @Test
    fun `uiState follows the preference reactively`() = runTest {
        val prefs = FakePrefs(EventReceiptConsent.UNDECIDED)
        val vm = EventReceiptConsentViewModel(prefs, isDebugBuild = true)

        assertTrue(vm.uiState.first { it != EventReceiptConsentUiState() }.showPrompt)

        prefs.consentFlow.value = EventReceiptConsent.DECLINED
        val declined = vm.uiState.first { !it.showPrompt }
        assertTrue(declined.blocked)

        prefs.consentFlow.value = EventReceiptConsent.ALLOWED
        val allowed = vm.uiState.first { !it.blocked }
        assertFalse(allowed.showPrompt)
    }

    @Test
    fun `DECLINED blocks in debug and never in release - asserted on a non-default emission`() = runTest {
        for (debug in listOf(true, false)) {
            // Start at UNDECIDED so the first non-default emission is the prompt, then decline —
            // the assertion is on the emission AFTER the decline, never on the stateIn initial.
            val prefs = FakePrefs(null)
            val vm = EventReceiptConsentViewModel(prefs, isDebugBuild = debug)
            prefs.consentFlow.value = EventReceiptConsent.UNDECIDED
            assertTrue(vm.uiState.first { it.showPrompt }.showPrompt)

            prefs.consentFlow.value = EventReceiptConsent.DECLINED
            val declined = vm.uiState.first { !it.showPrompt }
            assertEquals("debug=$debug", EventReceiptConsentUiState(blocked = debug), declined)
        }
    }

    @Test
    fun `the initial state is seeded from the current value - no frame of the default`() {
        val declined = EventReceiptConsentViewModel(FakePrefs(EventReceiptConsent.DECLINED), isDebugBuild = true)
        assertTrue("no subscriber yet — the stateIn initial value", declined.uiState.value.blocked)

        val unread = EventReceiptConsentViewModel(FakePrefs(null), isDebugBuild = true)
        assertTrue(unread.uiState.value.loading)
    }

    @Test
    fun `decisions write through the preference`() = runTest {
        val prefs = FakePrefs()
        val vm = EventReceiptConsentViewModel(prefs, isDebugBuild = false)

        vm.onDecision(allow = true)
        vm.onDecision(allow = false)

        assertEquals(listOf(EventReceiptConsent.ALLOWED, EventReceiptConsent.DECLINED), prefs.setCalls)
    }
}
