package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import org.junit.Assert.assertEquals
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

    @Test fun `key tiers`() {
        listOf("doordash|heb|" to 0, "doordash|heb|@12125" to 1, "doordash|target|02426" to 2)
            .forEach { (key, tier) -> assertEquals(key, tier, runner.keyTier(key)) }
    }

    @Test fun `tier transitions and prefix exceptions`() {
        data class Case(val name: String, val current: String?, val next: String, val downgrade: Boolean)
        val cases = listOf(
            Case("chain to address", "doordash|heb|", "doordash|heb|@12125", false),
            Case("chain to receipt", "doordash|target|", "doordash|target|02426", false),
            Case("address to receipt", "doordash|heb|@12125", "doordash|heb|799", false),
            Case("receipt to address", "doordash|target|02426", "doordash|target|@12125", true),
            Case("address to chain", "doordash|heb|@12125", "doordash|heb|", true),
            Case("receipt to chain", "doordash|target|02426", "doordash|target|", true),
            Case("identical key", "doordash|heb|@12125", "doordash|heb|@12125", false),
            // FIX 7: changing the platform prefix permits re-stamping at every tier.
            Case("platform upgrade chain", "_unknown|heb|", "doordash|heb|", false),
            Case("platform upgrade address", "_unknown|heb|@12125", "doordash|heb|@12125", false),
            Case("platform upgrade receipt", "_unknown|target|02426", "doordash|target|02426", false),
            Case("null current", null, "doordash|heb|@12125", false),
            Case("different chain", "doordash|target|02426", "doordash|heb|", false),
        )
        cases.forEach { (name, current, next, downgrade) ->
            assertEquals(name, downgrade, runner.isMonotonicDowngrade(current, next))
        }
    }
}
