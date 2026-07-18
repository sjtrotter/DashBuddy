package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #590 fill-in — [JsonRuleInterpreter] load is ATOMIC: a file that fails mid-load
 * leaves NO partial state — no rules from it are matchable AND no capability grants
 * are reconciled from it.
 *
 * The consent reconcile (#417) grants ACTUATION capabilities; it must happen only for
 * a bundle that fully passed compile + the sensitive-coverage gate. If reconcile ran
 * BEFORE a later reject (a dup-id whole-file reject, or a sensitive-coverage reject),
 * a rejected bundle's taps would be auto-granted while its rules never went live — a
 * fail-open grant leak. This pins the ordering.
 *
 * Result (green — not red-first): the ordering already holds. `loadSingle` is itself
 * atomic (it returns a complete [JsonRuleInterpreter.CompiledRuleBundle] or null,
 * never a partial), the dup-id reject returns null from `loadSingle` BEFORE `load`
 * ever reaches reconcile, and the sensitive-coverage reject in `load` returns BEFORE
 * reconcile too. This test locks that in against a future refactor moving reconcile
 * earlier.
 */
class LoadAtomicityTest {

    /** Records every reconcile call so a leaked grant is observable. */
    private class RecordingGrants : RuleCapabilityGrants {
        val reconcileCalls = mutableListOf<List<RuleCapability>>()
        override val grantedKeys: StateFlow<Set<String>> = MutableStateFlow(emptySet())
        override val capabilities: StateFlow<List<RuleCapability>> = MutableStateFlow(emptyList())
        override suspend fun reconcile(capabilities: List<RuleCapability>) {
            reconcileCalls += capabilities
        }
        override suspend fun isActionGranted(ruleId: String?, action: RuleAction): Boolean = false
        override suspend fun setGranted(key: String, granted: Boolean) = Unit
    }

    private fun interpreter(grants: RuleCapabilityGrants) =
        JsonRuleInterpreter(context = mock<Context>(), capabilityGrants = grants)

    // --- Rule-file builders (minimal valid doordash bundles) ---------------------

    private val sensitiveRule = """
        {
          "id": "doordash.screen.sensitive",
          "priority": 0,
          "overrideable": false,
          "parse": { "as": "sensitive" },
          "branches": [
            { "intent": "sensitive.balance", "require": { "exists": { "hasText": "Available Balance" } } }
          ]
        }
    """.trimIndent()

    private fun screenRule(id: String, priority: Int) = """
        {
          "id": "$id",
          "priority": $priority,
          "require": { "exists": { "hasText": "Dash Now" } }
        }
    """.trimIndent()

    private fun file(vararg screens: String) = """
        {
          "format_version": 2,
          "platform_id": "doordash.driver",
          "screens": [ ${screens.joinToString(",")} ]
        }
    """.trimIndent()

    private val validFile = file(sensitiveRule)

    // Earlier rules compile fine, then a duplicate id triggers a WHOLE-FILE reject.
    private val dupIdFile = file(
        sensitiveRule,
        screenRule("doordash.screen.dup", 10),
        screenRule("doordash.screen.dup", 11),
    )

    // Compiles, but ships NO sensitive rule → sensitive-coverage reject in load().
    private val noSensitiveFile = file(screenRule("doordash.screen.idle", 10))

    @Test
    fun `a dup-id whole-file reject reconciles NO capabilities and loads no rules`() = runTest {
        val grants = RecordingGrants()
        val interp = interpreter(grants)
        interp.load(dupIdFile, source = "asset:rules/doordash.json")

        assertFalse("a rejected file must not go live", interp.isLoaded)
        assertTrue(
            "reconcile ran for a rejected file — a fail-open grant leak (calls=${grants.reconcileCalls.size})",
            grants.reconcileCalls.isEmpty(),
        )
    }

    @Test
    fun `a sensitive-coverage reject reconciles NO capabilities and loads no rules`() = runTest {
        val grants = RecordingGrants()
        val interp = interpreter(grants)
        interp.load(noSensitiveFile, source = "asset:rules/doordash.json")

        assertFalse("a coverage-rejected file must not go live", interp.isLoaded)
        assertTrue(
            "reconcile ran despite the sensitive-coverage reject (calls=${grants.reconcileCalls.size})",
            grants.reconcileCalls.isEmpty(),
        )
    }

    @Test
    fun `a valid file goes live and reconciles exactly once (positive control)`() = runTest {
        val grants = RecordingGrants()
        val interp = interpreter(grants)
        interp.load(validFile, source = "asset:rules/doordash.json")

        assertTrue("a valid bundle must go live", interp.isLoaded)
        assertNotNull(interp.screenRuleset)
        assertEquals("valid load reconciles exactly once", 1, grants.reconcileCalls.size)
    }

    @Test
    fun `a bad load after a good one keeps the previous bundle and does not re-reconcile`() = runTest {
        val grants = RecordingGrants()
        val interp = interpreter(grants)

        interp.load(validFile, source = "asset:rules/doordash.json")
        val goodRuleset = interp.screenRuleset
        assertEquals(1, grants.reconcileCalls.size)

        // The failing load must be a no-op on the live state.
        interp.load(dupIdFile, source = "asset:rules/doordash.json")

        assertTrue("previous good bundle must remain live", interp.isLoaded)
        assertEquals("live ruleset must be unchanged by the rejected load", goodRuleset, interp.screenRuleset)
        assertEquals("no extra reconcile from the rejected load", 1, grants.reconcileCalls.size)
    }
}
