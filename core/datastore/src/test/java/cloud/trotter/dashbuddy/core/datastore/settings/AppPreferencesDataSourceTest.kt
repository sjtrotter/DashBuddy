package cloud.trotter.dashbuddy.core.datastore.settings

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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #318 — the driving/glance-mode pref: defaults false, and a write round-trips through the
 * same DataStore + flow the app reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppPreferencesDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newSource(dispatcher: TestDispatcher, fileName: String): AppPreferencesDataSource {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, fileName) },
        )
        return AppPreferencesDataSource(ds)
    }

    @Test
    fun `glanceMode defaults to false`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs1.preferences_pb")

        assertEquals(false, source.glanceMode.first())
    }

    @Test
    fun `setGlanceMode round-trips through the flow`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs2.preferences_pb")

        source.setGlanceMode(true)
        advanceUntilIdle()
        assertEquals(true, source.glanceMode.first())

        source.setGlanceMode(false)
        advanceUntilIdle()
        assertEquals(false, source.glanceMode.first())
    }
}
