package cloud.trotter.dashbuddy.core.datastore.capability

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #843/#1167 — the one-shot consent-schema migration marker. v2 clears both
 * grants and denials because the key shape changed, and stamps a version so it
 * runs exactly once (idempotent). #170 also covers atomic decision/receipt writes,
 * replacement, malformed records and persistence across store instances.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleCapabilityDataSourceTest {

    private val record = ConsentReceipt(1_791_244_800_000, "0.231.0", 1, true)

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSource(dispatcher: TestDispatcher, fileName: String): RuleCapabilityDataSource {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, fileName) },
        )
        return RuleCapabilityDataSource(ds)
    }

    /** The v2 key shape resets old decisions and stamps the migration marker (#1167). */
    @Test
    fun `migration clears grants and denials and stamps v2 once`() = runTest {
        for (stored in listOf(0, 1)) {
            val ds = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
                produceFile = { File(tmp.root, "cap-migrate-$stored.preferences_pb") },
            )
            val schemaVersion = intPreferencesKey("consent_schema_version")
            if (stored > 0) ds.edit { it[schemaVersion] = stored }
            val source = RuleCapabilityDataSource(ds)

            source.update { _, _, _ ->
                GrantSnapshot(setOf("stale-grant-a", "stale-grant-b"), setOf("denied-a"), mapOf("stale-grant-a" to record))
            }
            advanceUntilIdle()

            val ran = source.migrateConsentSchemaIfNeeded()
            advanceUntilIdle()

            assertTrue("first run reports it migrated", ran)
            assertTrue("granted set cleared", source.granted.first().isEmpty())
            assertTrue("denied set cleared", source.denied.first().isEmpty())
            assertTrue("receipts cleared", source.receipts.first().isEmpty())
            assertEquals("v2 marker stamped", 2, ds.data.first()[schemaVersion])
            source.update { _, _, _ -> GrantSnapshot(setOf("fresh-grant"), setOf("fresh-denial"), mapOf("fresh-grant" to record)) }
            assertFalse("second run no-ops", source.migrateConsentSchemaIfNeeded())
            assertEquals(setOf("fresh-grant"), source.granted.first())
            assertEquals(setOf("fresh-denial"), source.denied.first())
            assertEquals(mapOf("fresh-grant" to record), source.receipts.first())
        }
    }

    @Test
    fun `migration is a no-op on the second run - marker stamped`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cap-migrate2.preferences_pb")

        assertTrue("first run migrates", source.migrateConsentSchemaIfNeeded())
        advanceUntilIdle()

        source.update { _, _, _ -> GrantSnapshot(setOf("fresh-grant"), setOf("fresh-denial"), mapOf("fresh-grant" to record)) }
        advanceUntilIdle()

        assertFalse(
            "second run is a no-op — the version marker was stamped",
            source.migrateConsentSchemaIfNeeded(),
        )
        advanceUntilIdle()
        assertEquals(setOf("fresh-grant"), source.granted.first())
        assertEquals(setOf("fresh-denial"), source.denied.first())
    }

    @Test
    fun `a grant written after migration survives a redundant migration call`() = runTest {
        val source = newSource(StandardTestDispatcher(testScheduler), "cap-migrate3.preferences_pb")

        source.migrateConsentSchemaIfNeeded()
        advanceUntilIdle()

        source.update { granted, denied, receipts -> GrantSnapshot(granted + "fresh-grant", denied, receipts) }
        advanceUntilIdle()

        assertFalse("redundant migration no-ops", source.migrateConsentSchemaIfNeeded())
        advanceUntilIdle()
        assertTrue("fresh grant untouched", source.granted.first().contains("fresh-grant"))
    }

    @Test
    fun `decision and receipt commit in one edit and the opposite decision replaces the record`() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "atomic.preferences_pb") },
        )
        val grantKey = stringSetPreferencesKey("granted_capability_keys")
        val receiptKey = stringPreferencesKey("consent_receipts_json")
        var edits = 0
        val recording = object : DataStore<Preferences> {
            override val data = ds.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                ds.updateData { before ->
                    edits++
                    if (edits == 1) {
                        assertTrue(before[grantKey].isNullOrEmpty())
                        assertEquals(null, before[receiptKey])
                    }
                    transform(before).also { after ->
                        val receipts = Json.decodeFromString<Map<String, ConsentReceipt>>(after[receiptKey]!!)
                        assertEquals("grant and record agree in every edit", "k" in after[grantKey].orEmpty(), receipts.getValue("k").granted)
                    }
                }
        }
        val source = RuleCapabilityDataSource(recording)
        assertEquals(GrantSnapshot(emptySet(), emptySet(), emptyMap()), source.snapshot())
        source.update { granted, denied, receipts ->
            GrantSnapshot(granted + "k", denied - "k", receipts + ("k" to record))
        }
        assertEquals(1, edits)
        assertEquals(GrantSnapshot(setOf("k"), emptySet(), mapOf("k" to record)), source.snapshot())

        val revoked = record.copy(decidedAt = record.decidedAt + 1, granted = false)
        source.update { granted, denied, receipts ->
            GrantSnapshot(granted - "k", denied + "k", receipts + ("k" to revoked))
        }
        assertEquals(2, edits)
        assertEquals(GrantSnapshot(emptySet(), setOf("k"), mapOf("k" to revoked)), source.snapshot())
    }

    @Test
    fun `corrupt receipt JSON leaves grants and denials intact`() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "corrupt.preferences_pb") },
        )
        val source = RuleCapabilityDataSource(ds)
        source.update { _, _, _ -> GrantSnapshot(setOf("k"), setOf("denied"), mapOf("k" to record)) }
        for (corrupt in listOf("{broken", "[]", "{\"k\":{}}")) {
            ds.edit { it[stringPreferencesKey("consent_receipts_json")] = corrupt }
            assertEquals(emptyMap<String, ConsentReceipt>(), source.receipts.first())
            assertEquals(GrantSnapshot(setOf("k"), setOf("denied"), emptyMap()), source.snapshot())
        }
    }

    /** A newer build's extra receipt field must not erase every older record on a downgrade (#170 review). */
    @Test
    fun `a receipt carrying an unknown field still decodes and a decision beside it keeps the map`() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "forward.preferences_pb") },
        )
        val source = RuleCapabilityDataSource(ds)
        ds.edit {
            it[stringPreferencesKey("consent_receipts_json")] =
                """{"old":{"decidedAt":1,"appVersion":"0.240.0","disclosureRevision":2,"granted":true,"promptSurface":"sheet"}}"""
        }
        assertEquals(mapOf("old" to ConsentReceipt(1, "0.240.0", 2, true)), source.receipts.first())
        source.update { g, d, r -> GrantSnapshot(g + "k", d, r + ("k" to record)) }
        assertEquals(setOf("old", "k"), source.receipts.first().keys)
    }

    @Test
    fun `fresh store over the same file reads receipts back`() = runTest {
        val file = File(tmp.root, "reopen.preferences_pb")
        val job = Job()
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler) + job),
            produceFile = { file },
        )
        RuleCapabilityDataSource(ds).update { _, _, _ ->
            GrantSnapshot(setOf("k"), emptySet(), mapOf("k" to record))
        }
        job.cancelAndJoin()
        val reopened = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val source = RuleCapabilityDataSource(reopened)
        assertEquals(mapOf("k" to record), source.receipts.first())
        assertEquals(setOf("k"), source.granted.first())
    }
}
