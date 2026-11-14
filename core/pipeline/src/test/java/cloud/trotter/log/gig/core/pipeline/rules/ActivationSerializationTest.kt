package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import cloud.trotter.log.gig.domain.action.RuleAction
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #1568: two loads must not interleave reconcile and publish. If load A suspends inside capability
 * reconciliation while load B reconciles and publishes, A resuming would make its generation live
 * with B's capabilities, so the grant gate would check B's binding keys against A's rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivationSerializationTest {

    /** Suspends the second reconcile (load A's) until [gate] opens. */
    private class GatedGrants : RuleCapabilityGrants {
        val gate = CompletableDeferred<Unit>()
        val reconciled = mutableListOf<List<RuleCapability>>()
        private var calls = 0
        override val receipts = MutableStateFlow<Map<String, ConsentReceipt>>(emptyMap())
        override val grantedKeys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val deniedKeys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val capabilities: StateFlow<List<RuleCapability>> = MutableStateFlow(emptyList())
        override suspend fun reconcile(capabilities: List<RuleCapability>) {
            if (++calls == 2) gate.await()
            reconciled += capabilities
        }
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction): Boolean = false
        override suspend fun setGranted(key: String, granted: Boolean) = Unit
    }

    private val signingKey = TestRulesetSigning.keyPair()

    private fun offerFile(name: String) = TestRulesetSigning.verified(
        """
        { "format_version": 2, "platform_id": "doordash.driver", "screens": [
          { "id": "doordash.screen.sensitive.known", "priority": 0, "overrideable": false,
            "parse": { "as": "sensitive" }, "require": { "exists": { "hasText": "Available Balance" } } },
          { "id": "doordash.screen.$name", "priority": 30, "enables": ["accept_offer"],
            "bind": { "acceptButton": { "find": { "hasText": "Accept" } } },
            "require": { "exists": { "hasText": "Accept" } } } ] }
        """.trimIndent(),
        "test:rules",
        signingKey,
    )

    @Test
    fun `a load suspended in reconciliation finishes before the next one starts`() = runTest {
        val grants = GatedGrants()
        val interpreter = JsonRuleInterpreter(mock<Context>(), grants)
        interpreter.loadBundled(listOf(TestBundledFloor.uber))

        launch { interpreter.load(offerFile("offer_a")) }
        runCurrent()
        launch { interpreter.load(offerFile("offer_b")) }
        runCurrent()
        assertEquals("load B must wait while load A reconciles", 1, grants.reconciled.size)

        grants.gate.complete(Unit)
        advanceUntilIdle()

        assertNotNull(interpreter.generation?.screens?.ruleById("doordash.screen.offer_b"))
        assertEquals(listOf("doordash.screen.offer_b"), grants.reconciled.last().map { it.ruleId })
    }
}
