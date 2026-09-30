package cloud.trotter.dashbuddy.feature.settings

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import cloud.trotter.dashbuddy.domain.state.Platform
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
 * #422 PR 3 — the consent surface. Covers the pure read projection
 * ([buildConsentUiState]: per-source grouping, bundled vs downloaded, grant-state
 * join, deterministic ordering) and that the ViewModel's only write routes THROUGH
 * [RuleCapabilityGrants] with the correct grant/revoke boolean — the SSOT the
 * fail-closed engine gate reads. The fake records the write; it never becomes a
 * second gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CapabilityConsentViewModelTest {

    /** In-memory [EventReceiptPreferences] (#1151) — writes recorded, value drivable. */
    private class FakeEventReceipt(
        initial: EventReceiptConsent = EventReceiptConsent.UNDECIDED,
    ) : EventReceiptPreferences {
        private val _consent = MutableStateFlow<EventReceiptConsent?>(initial)
        override val consent: StateFlow<EventReceiptConsent?> = _consent
        val setCalls = mutableListOf<EventReceiptConsent>()
        override suspend fun set(consent: EventReceiptConsent) {
            setCalls += consent
            _consent.value = consent
        }
    }

    /** In-memory [RuleCapabilityGrants] — the enumeration + granted set are drivable, writes recorded. */
    private class FakeGrants : RuleCapabilityGrants {
        private val _capabilities = MutableStateFlow<List<RuleCapability>>(emptyList())
        private val _granted = MutableStateFlow<Set<String>>(emptySet())
        private val _denied = MutableStateFlow<Set<String>>(emptySet())
        override val capabilities: StateFlow<List<RuleCapability>> = _capabilities
        override val grantedKeys: StateFlow<Set<String>> = _granted
        override val deniedKeys: StateFlow<Set<String>> = _denied

        val setGrantedCalls = mutableListOf<Pair<String, Boolean>>()

        fun setEnumeration(caps: List<RuleCapability>) { _capabilities.value = caps }
        fun setGrantedKeys(keys: Set<String>) { _granted.value = keys }
        fun setDeniedKeys(keys: Set<String>) { _denied.value = keys }

        override suspend fun reconcile(capabilities: List<RuleCapability>) = error("unused")
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction) = error("unused")

        override suspend fun setGranted(key: String, granted: Boolean) {
            setGrantedCalls += key to granted
            // Mirror the real store so the UI reflects the change reactively.
            if (granted) {
                _granted.value = _granted.value + key
                _denied.value = _denied.value - key
            } else {
                _granted.value = _granted.value - key
                _denied.value = _denied.value + key
            }
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun cap(
        key: String,
        ruleId: String,
        action: RuleAction,
        source: String,
    ) = RuleCapability(
        ruleId = ruleId,
        action = action,
        targetBindName = action.targetBindName,
        key = key,
        source = source,
    )

    // =========================================================================
    // buildConsentUiState — the pure read projection
    // =========================================================================

    @Test
    fun `groups by source, marks bundled, joins grant state, orders deterministically`() {
        val state = buildConsentUiState(
            capabilities = listOf(
                // Deliberately out of order: decline before accept, fork before asset.
                cap("fork-accept", "uber.screen.offer", RuleAction.ACCEPT_OFFER, "fork:community"),
                cap("dd-decline", "doordash.screen.offer_popup", RuleAction.DECLINE_OFFER, "asset:rules/doordash.json"),
                cap("dd-accept", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER, "asset:rules/doordash.json"),
            ),
            grantedKeys = setOf("dd-accept"), // asset accept granted; decline revoked; fork pending
        )

        assertEquals(2, state.sources.size)
        // Bundled source sorts first.
        val bundled = state.sources[0]
        assertTrue(bundled.isBundled)
        assertEquals(Platform.DoorDash, bundled.platform)
        assertEquals(2, bundled.capabilities.size)
        // ACCEPT_OFFER (ordinal 0) before DECLINE_OFFER (ordinal 1) — deterministic.
        assertEquals(RuleAction.ACCEPT_OFFER, bundled.capabilities[0].action)
        assertTrue("granted key reflects true", bundled.capabilities[0].granted)
        assertFalse("revoked key reflects false", bundled.capabilities[1].granted)

        val downloaded = state.sources[1]
        assertFalse(downloaded.isBundled)
        assertEquals(Platform.Uber, downloaded.platform)
        assertFalse("a downloaded source is pending by default", downloaded.capabilities[0].granted)
    }

    @Test
    fun `empty enumeration yields empty state`() {
        assertTrue(buildConsentUiState(emptyList(), emptySet()).sources.isEmpty())
    }

    // =========================================================================
    // ViewModel — reactive state + write routing
    // =========================================================================

    @Test
    fun `uiState reflects the enumeration reactively`() = runTest {
        val grants = FakeGrants()
        grants.setEnumeration(
            listOf(cap("k1", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER, "asset:rules/doordash.json")),
        )
        grants.setGrantedKeys(setOf("k1"))
        val vm = CapabilityConsentViewModel(grants, FakeEventReceipt())

        // WhileSubscribed: collecting is what starts the upstream combine.
        val state = vm.uiState.first { it.sources.isNotEmpty() }
        assertEquals(1, state.sources.size)
        assertTrue(state.sources[0].capabilities[0].granted)
    }

    @Test
    fun `setGranted grant routes through the grant store`() = runTest {
        val grants = FakeGrants()
        val vm = CapabilityConsentViewModel(grants, FakeEventReceipt())

        vm.setGranted("k1", true)

        assertEquals(listOf("k1" to true), grants.setGrantedCalls)
    }

    @Test
    fun `setGranted revoke routes through the grant store as false and drops the key`() = runTest {
        val grants = FakeGrants()
        grants.setGrantedKeys(setOf("k1"))
        val vm = CapabilityConsentViewModel(grants, FakeEventReceipt())

        vm.setGranted("k1", false)

        assertEquals(listOf("k1" to false), grants.setGrantedCalls)
        // Revocation surfaces on the read side immediately (the engine gate then fails closed).
        assertFalse(grants.grantedKeys.value.contains("k1"))
    }

    // =========================================================================
    // #1151 — the "Screen events" switch (a FEATURE consent, not a grant)
    // =========================================================================

    @Test
    fun `event receipt switch reads ALLOWED only`() = runTest {
        for ((consent, expected) in listOf(
            EventReceiptConsent.UNDECIDED to false,
            EventReceiptConsent.DECLINED to false,
            EventReceiptConsent.ALLOWED to true,
        )) {
            // A non-empty enumeration lets the test wait for the COMBINED emission, not the default.
            val grants = FakeGrants()
            grants.setEnumeration(
                listOf(cap("k1", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER, "asset:rules/doordash.json")),
            )
            val vm = CapabilityConsentViewModel(grants, FakeEventReceipt(consent))
            val state = vm.uiState.first { it.sources.isNotEmpty() }
            assertEquals("$consent", expected, state.eventReceiptAllowed)
        }
    }

    @Test
    fun `the switch initial state is seeded from the current value`() {
        val vm = CapabilityConsentViewModel(FakeGrants(), FakeEventReceipt(EventReceiptConsent.ALLOWED))
        assertTrue("no subscriber yet — the stateIn initial value", vm.uiState.value.eventReceiptAllowed)
    }

    @Test
    fun `event receipt switch writes through the preference, never the grant store`() = runTest {
        val grants = FakeGrants()
        val receipt = FakeEventReceipt()
        val vm = CapabilityConsentViewModel(grants, receipt)

        vm.setEventReceiptAllowed(true)
        vm.setEventReceiptAllowed(false)

        assertEquals(listOf(EventReceiptConsent.ALLOWED, EventReceiptConsent.DECLINED), receipt.setCalls)
        assertTrue("a feature consent never touches the grant store", grants.setGrantedCalls.isEmpty())
    }
}
