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

    // #722 — the bubble's mode-adaptive gas quick-edit write paths.

    @Test
    fun `updateGasPriceManual writes the price and disables auto in one atomic edit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs3.preferences_pb")

        source.updateGasPriceManual(3.29f)
        advanceUntilIdle()

        assertEquals(3.29f, source.gasPrice.first())
        assertEquals(false, source.isGasPriceAuto.first())
    }

    @Test
    fun `updateGasPriceAuto writes the price and re-enables auto in one atomic edit`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs4.preferences_pb")

        // Start manual (as the stepper would leave it), then "Resume auto" should flip both back.
        source.updateGasPriceManual(3.29f)
        advanceUntilIdle()

        source.updateGasPriceAuto(3.61f)
        advanceUntilIdle()

        assertEquals(3.61f, source.gasPrice.first())
        assertEquals(true, source.isGasPriceAuto.first())
    }

    // #428 Half B — the spoken-offer language override.

    @Test
    fun `ttsLanguageTag defaults to null (follow system)`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs-tts1.preferences_pb")

        assertEquals(null, source.ttsLanguageTag.first())
    }

    @Test
    fun `setTtsLanguageTag round-trips and clears on null`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val source = newSource(dispatcher, "prefs-tts2.preferences_pb")

        source.setTtsLanguageTag("es")
        advanceUntilIdle()
        assertEquals("es", source.ttsLanguageTag.first())

        // null clears back to the system-locale default.
        source.setTtsLanguageTag(null)
        advanceUntilIdle()
        assertEquals(null, source.ttsLanguageTag.first())
    }
}
