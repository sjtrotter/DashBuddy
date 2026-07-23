package cloud.trotter.dashbuddy.core.data.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-policy tests for the consent grant store: the ALL-keys-granted coverage
 * semantics and the grant/deny set transform. The repository glue (DataStore IO
 * — reconcile-never-grants, undecided-never-fires, the one-shot migration) is
 * exercised in [RuleCapabilityRepositoryTest] against a real DataStore.
 */
class RuleCapabilityPolicyTest {

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

    // =========================================================================
    // applyGrantChange — the consent grant/revoke set transform (#422 PR 3)
    // =========================================================================

    @Test
    fun `granting adds the key and clears any prior denial`() {
        val (granted, denied) = applyGrantChange(
            key = "k1",
            grant = true,
            granted = setOf("k0"),
            denied = setOf("k1", "k2"),
        )
        assertEquals(setOf("k0", "k1"), granted)
        assertEquals(setOf("k2"), denied)
    }

    @Test
    fun `revoking removes the grant and persists an explicit denial`() {
        // The explicit denial is what moves the capability out of "undecided" so
        // the consent prompt never re-asks (durable opt-out, #843).
        val (granted, denied) = applyGrantChange(
            key = "k1",
            grant = false,
            granted = setOf("k1", "k2"),
            denied = emptySet(),
        )
        assertEquals(setOf("k2"), granted)
        assertEquals(setOf("k1"), denied)
    }
}
