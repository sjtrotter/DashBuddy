package cloud.trotter.dashbuddy.core.data.capability

import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy tests for the consent grant store (#417): the source auto-grant
 * selection and the ALL-keys-granted coverage semantics. The repository glue
 * (DataStore IO) is exercised through the engine-level gate tests in `:app`.
 */
class RuleCapabilityPolicyTest {

    private fun cap(
        key: String,
        source: String,
        ruleId: String = "doordash.screen.delivery_summary_collapsed",
        action: RuleAction = RuleAction.EXPAND_EARNINGS,
    ) = RuleCapability(
        ruleId = ruleId,
        action = action,
        targetBindName = action.targetBindName,
        key = key,
        source = source,
    )

    // =========================================================================
    // autoGrantSelection — the source policy
    // =========================================================================

    @Test
    fun `asset capabilities are auto-granted`() {
        val selected = autoGrantSelection(
            listOf(cap("k1", "asset:rules/doordash.json"), cap("k2", "asset:rules/uber.json")),
            denied = emptySet(),
        )
        assertEquals(setOf("k1", "k2"), selected)
    }

    @Test
    fun `non-asset sources are never auto-granted`() {
        val selected = autoGrantSelection(
            listOf(
                cap("k1", "cdn:https://rules.example.com/doordash.json"),
                cap("k2", "fork:community-rules"),
                cap("k3", "unknown"),
            ),
            denied = emptySet(),
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `an explicitly denied key is excluded from auto-grant`() {
        val selected = autoGrantSelection(
            listOf(cap("k1", "asset:rules/doordash.json"), cap("k2", "asset:rules/doordash.json")),
            denied = setOf("k2"),
        )
        assertEquals(setOf("k1"), selected)
    }

    // =========================================================================
    // actionKeysCovered — ALL-keys-granted semantics
    // =========================================================================

    @Test
    fun `coverage requires every enumerated key to be granted`() {
        // Two binding definitions for the same (rule, action): the fire-time
        // effect cannot prove which one aimed its target, so both must be
        // granted before the action may fire.
        assertFalse(actionKeysCovered(listOf("k1", "k2"), granted = setOf("k1")))
        assertTrue(actionKeysCovered(listOf("k1", "k2"), granted = setOf("k1", "k2")))
    }

    @Test
    fun `unknown or empty enumeration is not covered - fail closed`() {
        assertFalse(actionKeysCovered(null, granted = setOf("k1")))
        assertFalse(actionKeysCovered(emptyList(), granted = setOf("k1")))
    }
}
