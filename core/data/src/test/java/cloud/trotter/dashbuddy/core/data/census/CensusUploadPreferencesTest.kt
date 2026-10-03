package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CensusUploadPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `consent defaults off and URL grammar refuses without storing`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(io + Job())
        try {
            val ds = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "dev.preferences_pb") }
            val repo = DevSettingsRepository(DevSettingsDataSource(ds), true, io)
            assertFalse(repo.enabled.first())
            assertEquals("https://census.dashbuddy.trotter.cloud", repo.baseUrl.first())
            assertTrue(repo.setCensusBaseUrl("https://census.example.test:8443"))
            for (url in listOf("http://example.test", "https://EXAMPLE.test", "https://example.test/", "https://example.test?q=1",
                "https://example.test:1", "https://example.test:123456", "https://user@example.test", "https://example.test#fragment")) {
                assertFalse(repo.setCensusBaseUrl(url))
                assertEquals("https://census.example.test:8443", repo.baseUrl.first())
            }
            repo.setCensusUploadEnabled(true)
            assertTrue(repo.enabled.first())
            val release = DevSettingsRepository(DevSettingsDataSource(ds), false, io)
            assertFalse(release.enabled.first())
            release.setCensusUploadEnabled(true)
            assertFalse(repo.enabled.first())
        } finally {
            scope.cancel()
        }
    }
}
