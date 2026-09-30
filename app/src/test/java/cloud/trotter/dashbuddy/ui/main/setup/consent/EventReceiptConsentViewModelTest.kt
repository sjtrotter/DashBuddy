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

    private class FakePrefs(
        initial: EventReceiptConsent = EventReceiptConsent.UNDECIDED,
        loaded: Boolean = true,
    ) : EventReceiptPreferences {
        val consentFlow = MutableStateFlow(initial)
        val loadedFlow = MutableStateFlow(loaded)
        override val consent: StateFlow<EventReceiptConsent> = consentFlow
        override val loaded: StateFlow<Boolean> = loadedFlow
        val setCalls = mutableListOf<EventReceiptConsent>()
        override suspend fun set(consent: EventReceiptConsent) {
            setCalls += consent
            consentFlow.value = consent
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    // ---- pure projection ------------------------------------------------------------------

    @Test
    fun `UNDECIDED shows the prompt once loaded`() {
        for (debug in listOf(true, false)) {
            val s = buildEventReceiptConsentState(EventReceiptConsent.UNDECIDED, loaded = true, isDebugBuild = debug)
            assertTrue(s.showPrompt)
            assertFalse(s.blocked)
        }
    }

    @Test
    fun `the prompt never flashes before the store is read`() {
        val s = buildEventReceiptConsentState(EventReceiptConsent.UNDECIDED, loaded = false, isDebugBuild = true)
        assertFalse(s.showPrompt)
        assertFalse(s.blocked)
    }

    @Test
    fun `ALLOWED and DECLINED hide the prompt`() {
        for (consent in listOf(EventReceiptConsent.ALLOWED, EventReceiptConsent.DECLINED)) {
            for (debug in listOf(true, false)) {
                assertFalse(buildEventReceiptConsentState(consent, loaded = true, isDebugBuild = debug).showPrompt)
            }
        }
    }

    @Test
    fun `the block is reachable only when DEBUG and DECLINED`() {
        for (consent in EventReceiptConsent.entries) {
            for (debug in listOf(true, false)) {
                for (loaded in listOf(true, false)) {
                    val blocked = buildEventReceiptConsentState(consent, loaded, debug).blocked
                    assertEquals(
                        "consent=$consent debug=$debug loaded=$loaded",
                        debug && consent == EventReceiptConsent.DECLINED,
                        blocked,
                    )
                }
            }
        }
    }

    // ---- ViewModel ------------------------------------------------------------------------

    @Test
    fun `uiState follows the preference reactively`() = runTest {
        val prefs = FakePrefs(EventReceiptConsent.UNDECIDED)
        val vm = EventReceiptConsentViewModel(prefs, isDebugBuild = true)

        assertTrue(vm.uiState.first { it.showPrompt }.showPrompt)

        prefs.consentFlow.value = EventReceiptConsent.DECLINED
        val declined = vm.uiState.first { !it.showPrompt }
        assertTrue(declined.blocked)

        prefs.consentFlow.value = EventReceiptConsent.ALLOWED
        val allowed = vm.uiState.first { !it.blocked }
        assertFalse(allowed.showPrompt)
    }

    @Test
    fun `a release build never blocks on DECLINED`() = runTest {
        val prefs = FakePrefs(EventReceiptConsent.DECLINED)
        val vm = EventReceiptConsentViewModel(prefs, isDebugBuild = false)
        prefs.loadedFlow.value = true
        val s = vm.uiState.first()
        assertFalse(s.blocked)
        assertFalse(s.showPrompt)
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
