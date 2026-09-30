package cloud.trotter.dashbuddy.ui.main.setup.consent

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import cloud.trotter.dashbuddy.domain.settings.EventReceiptPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #1151 review NN1 — the front door's FIRST choice, read from BOTH real ViewModels' initial state
 * (no subscriber, no emission yet): with one undecided capability and an UNDECIDED event receipt it
 * must be CAPABILITIES. An unseeded capability VM (empty rows) would pick EVENT_RECEIPT first and
 * then swap pages with no decision taken — the picker-only tests cannot see that.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FrontDoorFirstChoiceTest {

    // A StandardTestDispatcher never runs the sharing coroutine here, so only the seeded
    // stateIn initial values are observed — exactly the first frame the host composes.
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private class OneUndecidedGrants(published: Boolean = true) : RuleCapabilityGrants {
        override val loaded: StateFlow<Boolean> = MutableStateFlow(published)
        override val capabilities: StateFlow<List<RuleCapability>> = MutableStateFlow(
            if (!published) emptyList() else listOf(
                RuleCapability(
                    ruleId = "doordash.screen.offer_popup",
                    action = RuleAction.ACCEPT_OFFER,
                    targetBindName = RuleAction.ACCEPT_OFFER.targetBindName,
                    key = "k1",
                    source = "asset:rules/doordash.json",
                ),
            ),
        )
        override val grantedKeys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val deniedKeys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override suspend fun reconcile(capabilities: List<RuleCapability>) = error("unused")
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction) = error("unused")
        override suspend fun setGranted(key: String, granted: Boolean) = error("unused")
    }

    private class UndecidedReceipt : EventReceiptPreferences {
        override val consent: StateFlow<EventReceiptConsent?> =
            MutableStateFlow(EventReceiptConsent.UNDECIDED)
        override suspend fun set(consent: EventReceiptConsent): Boolean = error("unused")
    }

    @Test
    fun `the first pick over both seeded view models is CAPABILITIES`() {
        val capabilities = ConsentPromptViewModel(OneUndecidedGrants())
        val receipt = EventReceiptConsentViewModel(UndecidedReceipt(), isDebugBuild = false)

        val first = pickFrontDoorPrompt(
            capabilitiesReady = capabilities.uiState.value.ready,
            capabilityRowsPending = capabilities.uiState.value.rows.isNotEmpty(),
            eventReceiptReady = receipt.uiState.value.ready,
            eventReceiptPending = receipt.uiState.value.showPrompt,
            deferrals = FrontDoorDeferrals(),
        )

        assertEquals(FrontDoorPrompt.CAPABILITIES, first)
    }

    @Test
    fun `before the rule load publishes, the door shows nothing - not the event receipt`() {
        val capabilities = ConsentPromptViewModel(OneUndecidedGrants(published = false))
        val receipt = EventReceiptConsentViewModel(UndecidedReceipt(), isDebugBuild = false)

        val first = pickFrontDoorPrompt(
            capabilitiesReady = capabilities.uiState.value.ready,
            capabilityRowsPending = capabilities.uiState.value.rows.isNotEmpty(),
            eventReceiptReady = receipt.uiState.value.ready,
            eventReceiptPending = receipt.uiState.value.showPrompt,
            deferrals = FrontDoorDeferrals(),
        )

        assertNull("an empty, unpublished enumeration must not hand the door to the receipt", first)
    }
}
