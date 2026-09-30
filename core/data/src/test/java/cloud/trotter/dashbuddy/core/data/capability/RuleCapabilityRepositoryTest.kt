package cloud.trotter.dashbuddy.core.data.capability

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Repository glue over a REAL Preferences DataStore (#843): the Play-policy core
 * — reconcile grants NOTHING (any source, incl. asset), an undecided capability
 * never fires the gate, an explicit grant is the only path to firing, denials
 * are durable, and the one-shot schema migration clears grants / keeps denials /
 * stamps its marker exactly once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleCapabilityRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRepo(
        scope: TestScope,
        fileName: String,
    ): Pair<RuleCapabilityRepository, RuleCapabilityDataSource> {
        val storeScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + Job())
        val ds = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, fileName) },
        )
        val dataSource = RuleCapabilityDataSource(ds)
        return RuleCapabilityRepository(dataSource, storeScope) to dataSource
    }

    private fun cap(
        key: String,
        source: String,
        ruleId: String = "doordash.screen.offer_popup",
        action: RuleAction = RuleAction.ACCEPT_OFFER,
    ) = RuleCapability(
        ruleId = ruleId,
        action = action,
        targetBindName = action.targetBindName,
        key = key,
        source = source,
    )

    // =========================================================================
    // reconcile grants NOTHING (the Play-policy core)
    // =========================================================================

    @Test
    fun `reconcile grants nothing - even asset-prefixed sources`() = runTest {
        val (repo, _) = newRepo(this, "recon1.preferences_pb")

        repo.reconcile(
            listOf(
                cap("asset-k", "asset:rules/doordash.json"),
                cap("cdn-k", "cdn:https://rules.example.com/uber.json", ruleId = "uber.screen.offer"),
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "reconcile must never populate the granted set (#843)",
            repo.grantedKeys.first().isEmpty(),
        )
        // The enumeration IS published (the consent surface needs it).
        assertEquals(2, repo.capabilities.first().size)
    }

    @Test
    fun `an undecided asset capability never fires the gate`() = runTest {
        val (repo, _) = newRepo(this, "recon2.preferences_pb")

        repo.reconcile(listOf(cap("asset-k", "asset:rules/doordash.json")))
        advanceUntilIdle()

        assertFalse(
            "an enumerated-but-undecided capability is not granted — fail closed",
            repo.isActionGranted("doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER),
        )
    }

    @Test
    fun `an explicit grant is the only path to firing`() = runTest {
        val (repo, _) = newRepo(this, "recon3.preferences_pb")

        repo.reconcile(listOf(cap("asset-k", "asset:rules/doordash.json")))
        advanceUntilIdle()
        assertFalse(repo.isActionGranted("doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER))

        repo.setGranted("asset-k", true)
        advanceUntilIdle()

        assertTrue(
            "after an explicit user grant the gate fires",
            repo.isActionGranted("doordash.screen.offer_popup", RuleAction.ACCEPT_OFFER),
        )
    }

    // =========================================================================
    // setGranted round-trip + durable denial
    // =========================================================================

    @Test
    fun `setGranted round-trips grant then deny with a durable denial`() = runTest {
        val (repo, _) = newRepo(this, "grant1.preferences_pb")

        repo.setGranted("k1", true)
        advanceUntilIdle()
        assertTrue(repo.grantedKeys.first().contains("k1"))
        assertFalse(repo.deniedKeys.first().contains("k1"))

        repo.setGranted("k1", false)
        advanceUntilIdle()
        assertFalse(repo.grantedKeys.first().contains("k1"))
        assertTrue("a deny persists an explicit denial", repo.deniedKeys.first().contains("k1"))
    }

    // =========================================================================
    // one-shot schema migration
    // =========================================================================

    @Test
    fun `migration clears grants keeps denials and stamps the marker once`() = runTest {
        val (repo, ds) = newRepo(this, "migrate1.preferences_pb")

        // Pre-#843 store: an auto-granted key AND an explicit denial.
        ds.update { _, _ -> setOf("stale-auto-grant") to setOf("explicit-deny") }
        advanceUntilIdle()

        repo.migrateConsentSchemaIfNeeded()
        advanceUntilIdle()

        assertTrue("stale grant cleared", repo.grantedKeys.first().isEmpty())
        assertEquals("denial preserved", setOf("explicit-deny"), repo.deniedKeys.first())
    }

    @Test
    fun `migration is idempotent - a second run does not re-clear a fresh grant`() = runTest {
        val (repo, _) = newRepo(this, "migrate2.preferences_pb")

        repo.migrateConsentSchemaIfNeeded()
        advanceUntilIdle()

        // User grants AFTER the migration already ran.
        repo.setGranted("post-migration-grant", true)
        advanceUntilIdle()

        // A second migration must no-op (marker already stamped) — the fresh
        // grant survives.
        repo.migrateConsentSchemaIfNeeded()
        advanceUntilIdle()

        assertTrue(
            "a fresh grant survives a redundant migration call",
            repo.grantedKeys.first().contains("post-migration-grant"),
        )
    }
}
