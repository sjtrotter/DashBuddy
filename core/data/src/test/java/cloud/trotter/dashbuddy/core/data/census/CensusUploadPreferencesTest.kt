package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CensusUploadPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `last run roundtrips decodes unknown tokens fail closed and a reset keeps the deferral deadline`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(io + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "last-run.preferences_pb") }
            val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            repo.setCensusUploadEnabled(true)
            repo.setCensusPolicy(Json.parseToJsonElement("""{"k":10}""").jsonObject)
            val run = CensusLastRun(5L, CensusRunOutcome.UPLOADED, 12, envelopesPosted = 4)
            repo.setCensusLastRun(run)
            assertEquals(run, repo.censusLastRun.first())
            assertEquals("uploaded 12", repo.censusLastRun.first()?.token())
            ds.edit { it[stringPreferencesKey("census_last_run_outcome")] = "bogus" }
            assertEquals(CensusRunOutcome.UNKNOWN, repo.censusLastRun.first()?.outcome)
            assertEquals(5L, repo.censusLastRun.first()?.atMillis)
            repo.setNextAllowedAtMillis(99)

            repo.recordCensusReset(CensusLastRun(7L, CensusRunOutcome.RESET))

            assertEquals(CensusLastRun(7L, CensusRunOutcome.RESET), repo.censusLastRun.first())
            assertEquals(99L, repo.nextAllowedAtMillis.first()) // Server policy survives an identity reset.
            assertTrue(repo.censusPolicy.first().isEmpty())
            assertTrue(repo.enabled.first())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `consent off clears sharing in exactly one datastore transaction and consent alone cannot restore it`() = runTest {
        val edits = mutableListOf<Preferences>()
        val ds = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it; edits += it }
        }
        val source = DevSettingsDataSource(ds)
        val repo = DevSettingsRepository(source, true, StandardTestDispatcher(testScheduler))
        repo.setCensusUploadEnabled(true)
        repo.setCensusShareCaptures(true)
        edits.clear()

        repo.setCensusUploadEnabled(false)

        assertEquals(1, edits.size)
        assertFalse(source.censusUploadEnabled.first())
        assertFalse(source.censusShareCaptures.first())
        assertFalse(repo.censusShareCaptures.first())
        repo.setCensusUploadEnabled(true)
        assertTrue(repo.censusUploadEnabled.first())
        assertFalse(repo.censusShareCaptures.first())
    }

    @Test fun `legacy last run without envelope count reads zero`() = runTest {
        val ds = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        ds.edit {
            it[androidx.datastore.preferences.core.longPreferencesKey("census_last_run_at_millis")] = 5L
            it[stringPreferencesKey("census_last_run_outcome")] = "uploaded"
        }
        val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, StandardTestDispatcher(testScheduler))
        assertEquals(CensusLastRun(5L, CensusRunOutcome.UPLOADED), repo.censusLastRun.first())
    }

    @Test fun `consent defaults off and base URL is constant`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(io + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "dev.preferences_pb") }
            val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            assertFalse(repo.enabled.first())
            assertFalse(repo.censusShareCaptures.first())
            assertEquals("https://census.dashbuddy.trotter.cloud", repo.baseUrl.first())
            ds.edit { it[stringPreferencesKey("census_base_url")] = "https://obsolete.example.test" }
            assertEquals("https://census.dashbuddy.trotter.cloud", repo.baseUrl.first())
            repo.setCensusUploadEnabled(true)
            assertTrue(repo.enabled.first())
            repo.setCensusShareCaptures(true)
            assertTrue(repo.censusShareCaptures.first())
            repo.setCensusUploadEnabled(false)
            assertFalse(repo.censusShareCaptures.first())
            repo.setCensusUploadEnabled(true)
            assertFalse(repo.censusShareCaptures.first())
            val release = DevSettingsRepository(DevSettingsDataSource(ds), false, io)
            assertFalse(release.enabled.first())
            release.setCensusShareCaptures(true)
            assertFalse(release.censusShareCaptures.first())
            release.setCensusUploadEnabled(true)
            assertFalse(repo.enabled.first())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `every consent change clears the persisted deadline`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(io + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "toggle.preferences_pb") }
            val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            for (enabled in listOf(true, false, true)) {
                repo.setNextAllowedAtMillis(Long.MAX_VALUE)
                repo.setCensusUploadEnabled(enabled)
                assertEquals(enabled, repo.enabled.first())
                assertEquals(0L, repo.nextAllowedAtMillis.first())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `accepted schemas persist across repository recreation distinguish empty and reset to screens only`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(io + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "schemas.preferences_pb") }
            val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            assertEquals(setOf(SkeletonSchema.SCHEMA_ID), repo.acceptedSchemaIds.first())
            repo.setCensusPolicy(Json.parseToJsonElement(
                """{"acceptedSchemaIds":["uinode.skeleton.v1","notification.skeleton.v1"],"k":10}""",
            ).jsonObject)
            val reopened = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            assertEquals(setOf(SkeletonSchema.SCHEMA_ID, NotificationSkeletonSchema.SCHEMA_ID), reopened.acceptedSchemaIds.first())
            repo.setCensusPolicy(Json.parseToJsonElement("""{"acceptedSchemaIds":[]}""").jsonObject)
            assertEquals(emptySet<String>(), reopened.acceptedSchemaIds.first())
            repo.setCensusPolicy(Json.parseToJsonElement("""{"acceptedSchemaIds":[null,1,{},"notification.skeleton.v1"]}""").jsonObject)
            assertEquals(setOf(NotificationSkeletonSchema.SCHEMA_ID), reopened.acceptedSchemaIds.first())
            repo.recordCensusReset(CensusLastRun(7L, CensusRunOutcome.RESET))
            assertEquals(setOf(SkeletonSchema.SCHEMA_ID), reopened.acceptedSchemaIds.first())
        } finally {
            scope.cancel()
        }
    }

    @Test fun `policy persists five integer limits and accepted schemas and removes legacy raw JSON`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(io + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "policy.preferences_pb") }
            val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            ds.edit { it[stringPreferencesKey("census_policy")] = "legacy raw JSON" }
            val policy = """{"dailySkeletonBudget":300,"maxBatchItems":100,"maxBatchBytes":921600,
                "maxSkeletonBytes":65536,"k":10,"unknownLimit":123,"padding":"${"x".repeat(50_000)}"}"""
            repo.setCensusPolicy(Json.parseToJsonElement(policy).jsonObject)
            val expected = mapOf("dailySkeletonBudget" to 300, "maxBatchItems" to 100,
                "maxBatchBytes" to 921600, "maxSkeletonBytes" to 65536, "k" to 10)
            assertEquals(expected, repo.censusPolicy.first())
            assertEquals(expected.mapKeys { "census_policy_${it.key}" } +
                mapOf("census_policy_accepted_schema_ids" to emptySet<String>()),
                ds.data.first().asMap().mapKeys { it.key.name })
            assertEquals(emptySet<String>(), repo.acceptedSchemaIds.first())
            repo.setCensusPolicy(Json.parseToJsonElement("""{"k":"10","maxBatchItems":2147483648,"maxBatchBytes":1.5}""").jsonObject)
            assertTrue(repo.censusPolicy.first().isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
