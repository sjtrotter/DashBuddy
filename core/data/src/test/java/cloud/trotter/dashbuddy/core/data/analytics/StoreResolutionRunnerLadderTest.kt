package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * #773 — the ladder-aware monotonic backstop ([StoreResolutionRunner.isMonotonicDowngrade]) in
 * isolation: the running-key TIER order chain-only (0) < address `@` (1) < receipt (2), compared ONLY
 * within the same platform+chain prefix. Pure key-string logic, so it is tested directly rather than
 * reconstructed end-to-end through the fold.
 */
class StoreResolutionRunnerLadderTest {

    private val runner = StoreResolutionRunner(mock<AnalyticsDao>())

    // ── keyTier ─────────────────────────────────────────────────────────

    @Test
    fun `keyTier orders chain-only below address below receipt`() {
        assertTrue(runner.keyTier("doordash|heb|") == 0)
        assertTrue(runner.keyTier("doordash|heb|@12125") == 1)
        assertTrue(runner.keyTier("doordash|target|02426") == 2)
    }

    // ── upgrades (NOT downgrades) ───────────────────────────────────────

    @Test
    fun `chain-only to address is an upgrade — re-stamp allowed`() {
        assertFalse(runner.isMonotonicDowngrade("doordash|heb|", "doordash|heb|@12125"))
    }

    @Test
    fun `chain-only to receipt is an upgrade`() {
        assertFalse(runner.isMonotonicDowngrade("doordash|target|", "doordash|target|02426"))
    }

    @Test
    fun `address to receipt is an upgrade — re-stamp allowed`() {
        assertFalse(runner.isMonotonicDowngrade("doordash|heb|@12125", "doordash|heb|799"))
    }

    // ── blocked downgrades ──────────────────────────────────────────────

    @Test
    fun `receipt to address is BLOCKED — the genuinely new #773 edge`() {
        assertTrue(runner.isMonotonicDowngrade("doordash|target|02426", "doordash|target|@12125"))
    }

    @Test
    fun `address to chain-only is BLOCKED`() {
        assertTrue(runner.isMonotonicDowngrade("doordash|heb|@12125", "doordash|heb|"))
    }

    @Test
    fun `receipt to chain-only is BLOCKED`() {
        assertTrue(runner.isMonotonicDowngrade("doordash|target|02426", "doordash|target|"))
    }

    // ── no-churn + platform-upgrade + guards ────────────────────────────

    @Test
    fun `an identical key is not a downgrade (value-compare no-churn)`() {
        assertFalse(runner.isMonotonicDowngrade("doordash|heb|@12125", "doordash|heb|@12125"))
    }

    @Test
    fun `a platform upgrade across platforms is allowed at every tier (FIX 7 stays intact)`() {
        // _unknown|heb|… → doordash|heb|… : different platform+chain prefix, so never a downgrade even
        // though the tiers are equal. This is the FIX 7 re-stamp the ladder must not break.
        assertFalse(runner.isMonotonicDowngrade("_unknown|heb|", "doordash|heb|"))
        assertFalse(runner.isMonotonicDowngrade("_unknown|heb|@12125", "doordash|heb|@12125"))
        assertFalse(runner.isMonotonicDowngrade("_unknown|target|02426", "doordash|target|02426"))
    }

    @Test
    fun `a null current key is never a downgrade`() {
        assertFalse(runner.isMonotonicDowngrade(null, "doordash|heb|@12125"))
    }

    @Test
    fun `a different chain is never a downgrade (prefix guard)`() {
        assertFalse(runner.isMonotonicDowngrade("doordash|target|02426", "doordash|heb|"))
    }
}
