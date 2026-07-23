package cloud.trotter.dashbuddy.ui.main.setup.consent

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
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
 * #843 — the prompted per-capability consent surface. Covers the pure trigger
 * projection ([buildConsentPromptState]: undecided = capabilities − granted −
 * denied, deterministic order, distinct keys) and that the ViewModel's only
 * write routes THROUGH [RuleCapabilityGrants] with the correct Allow/Don't-allow
 * boolean, removing the answered row reactively. The fake records the write; it
 * never becomes a second gate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentPromptViewModelTest {

    /** In-memory [RuleCapabilityGrants] — enumeration + granted/denied drivable, writes recorded. */
    private class FakeGrants : RuleCapabilityGrants {
        private val _capabilities = MutableStateFlow<List<RuleCapability>>(emptyList())
        private val _granted = MutableStateFlow<Set<String>>(emptySet())
        private val _denied = MutableStateFlow<Set<String>>(emptySet())
        override val capabilities: StateFlow<List<RuleCapability>> = _capabilities
        override val grantedKeys: StateFlow<Set<String>> = _granted
        override val deniedKeys: StateFlow<Set<String>> = _denied

        val setGrantedCalls = mutableListOf<Pair<String, Boolean>>()

        fun setEnumeration(caps: List<RuleCapability>) { _capabilities.value = caps }

        override suspend fun reconcile(capabilities: List<RuleCapability>) = error("unused")
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction) = error("unused")

        override suspend fun setGranted(key: String, granted: Boolean) {
            setGrantedCalls += key to granted
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
        source: String = "asset:rules/doordash.json",
    ) = RuleCapability(
        ruleId = ruleId,
        action = action,
        targetBindName = action.targetBindName,
        key = key,
        source = source,
    )

    // =========================================================================
    // buildConsentPromptState — the pure trigger projection
    // =========================================================================

    @Test
    fun `only undecided capabilities become rows`() {
        val state = buildConsentPromptState(
            capabilities = listOf(
                cap("granted-k", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER),
                cap("denied-k", "doordash.screen.offer_popup", RuleAction.DECLINE_OFFER),
                cap("undecided-k", "doordash.screen.delivery_summary_collapsed", RuleAction.EXPAND_EARNINGS),
            ),
            grantedKeys = setOf("granted-k"),
            deniedKeys = setOf("denied-k"),
        )

        assertEquals(1, state.rows.size)
        assertEquals("undecided-k", state.rows[0].key)
        assertEquals(RuleAction.EXPAND_EARNINGS, state.rows[0].action)
        assertEquals(Platform.DoorDash, state.rows[0].platform)
        assertTrue(state.rows[0].isBundled)
    }

    @Test
    fun `all-decided yields no rows - the trigger predicate is false`() {
        val state = buildConsentPromptState(
            capabilities = listOf(
                cap("a", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER),
                cap("b", "doordash.screen.offer_popup", RuleAction.DECLINE_OFFER),
            ),
            grantedKeys = setOf("a"),
            deniedKeys = setOf("b"),
        )
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `rows are deterministically ordered by action then rule id`() {
        val state = buildConsentPromptState(
            capabilities = listOf(
                cap("decline-k", "doordash.screen.offer_popup", RuleAction.DECLINE_OFFER),
                cap("accept-k", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER),
            ),
            grantedKeys = emptySet(),
            deniedKeys = emptySet(),
        )
        // ACCEPT_OFFER (ordinal 0) before DECLINE_OFFER (ordinal 1).
        assertEquals(RuleAction.ACCEPT_OFFER, state.rows[0].action)
        assertEquals(RuleAction.DECLINE_OFFER, state.rows[1].action)
    }

    @Test
    fun `a duplicate key across sources yields a single row`() {
        val state = buildConsentPromptState(
            capabilities = listOf(
                cap("dupe", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER, "asset:rules/doordash.json"),
                cap("dupe", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER, "cdn:https://x/doordash.json"),
            ),
            grantedKeys = emptySet(),
            deniedKeys = emptySet(),
        )
        assertEquals(1, state.rows.size)
    }

    // =========================================================================
    // ViewModel — reactive state + write routing
    // =========================================================================

    @Test
    fun `uiState lists only undecided rows reactively`() = runTest {
        val grants = FakeGrants()
        grants.setEnumeration(
            listOf(cap("k1", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER)),
        )
        val vm = ConsentPromptViewModel(grants)

        val state = vm.uiState.first { it.rows.isNotEmpty() }
        assertEquals(1, state.rows.size)
        assertEquals("k1", state.rows[0].key)
    }

    @Test
    fun `Allow routes a grant through the store and drops the row`() = runTest {
        val grants = FakeGrants()
        grants.setEnumeration(
            listOf(cap("k1", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER)),
        )
        val vm = ConsentPromptViewModel(grants)
        vm.uiState.first { it.rows.isNotEmpty() }

        vm.onDecision("k1", true)

        assertEquals(listOf("k1" to true), grants.setGrantedCalls)
        // Granting removes it from the undecided set → row disappears.
        assertTrue(vm.uiState.first().rows.isEmpty())
    }

    @Test
    fun `Don't allow routes a denial through the store and drops the row`() = runTest {
        val grants = FakeGrants()
        grants.setEnumeration(
            listOf(cap("k1", "doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER)),
        )
        val vm = ConsentPromptViewModel(grants)
        vm.uiState.first { it.rows.isNotEmpty() }

        vm.onDecision("k1", false)

        assertEquals(listOf("k1" to false), grants.setGrantedCalls)
        assertFalse(grants.grantedKeys.value.contains("k1"))
        assertTrue(grants.deniedKeys.value.contains("k1"))
        // A denial also leaves the undecided set → row disappears (never re-prompts).
        assertTrue(vm.uiState.first().rows.isEmpty())
    }
}
