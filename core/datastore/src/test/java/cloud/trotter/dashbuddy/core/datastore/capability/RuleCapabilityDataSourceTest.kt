package cloud.trotter.dashbuddy.core.datastore.capability

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
 * #843 — the one-shot consent-schema migration marker. The migration clears the
 * granted set (a pre-#843 store may hold auto-granted keys that were never an
 * explicit user act), preserves explicit denials, and stamps a version so it
 * runs exactly once (idempotent). The grant/deny [RuleCapabilityDataSource.update]
 * round-trip is covered by the repository-level test in :core:data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleCapabilityDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSource(dispatcher: TestDispatcher, fileName: String): RuleCapabilityDataSource {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, fileName) },
        )
        return RuleCapabilityDataSource(ds)
    }

    @Test
    fun `migration clears grants keeps denials and reports it ran the first time`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cap-migrate1.preferences_pb")

        source.update { _, _ -> setOf("stale-grant-a", "stale-grant-b") to setOf("denied-a") }
        advanceUntilIdle()

        val ran = source.migrateConsentSchemaIfNeeded()
        advanceUntilIdle()

        assertTrue("first run reports it migrated", ran)
        assertTrue("granted set cleared", source.granted.first().isEmpty())
        assertEquals("denial preserved", setOf("denied-a"), source.denied.first())
    }

    @Test
    fun `migration is a no-op on the second run - marker stamped`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cap-migrate2.preferences_pb")

        assertTrue("first run migrates", source.migrateConsentSchemaIfNeeded())
        advanceUntilIdle()

        assertFalse(
            "second run is a no-op — the version marker was stamped",
            source.migrateConsentSchemaIfNeeded(),
        )
        advanceUntilIdle()
    }

    @Test
    fun `a grant written after migration survives a redundant migration call`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cap-migrate3.preferences_pb")

        source.migrateConsentSchemaIfNeeded()
        advanceUntilIdle()

        source.update { granted, denied -> (granted + "fresh-grant") to denied }
        advanceUntilIdle()

        assertFalse("redundant migration no-ops", source.migrateConsentSchemaIfNeeded())
        advanceUntilIdle()
        assertTrue("fresh grant untouched", source.granted.first().contains("fresh-grant"))
    }
}
