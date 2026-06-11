package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #364 — platform toggling must be one atomic DataStore transform. The old
 * read-modify-write (`.first()` then a separate `setEnabledPlatforms`) let
 * concurrent toggles interleave and lose updates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformPreferencesDataSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `concurrent atomic updates never lose a toggle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "prefs.preferences_pb") },
        )
        val source = PlatformPreferencesDataSource(ds)

        // 50 concurrent single-platform enables, each an atomic transform.
        val platforms = (1..50).map { "platform-$it" }
        platforms.forEach { wire ->
            launch(dispatcher) {
                source.updateEnabledPlatforms { saved -> (saved ?: emptySet()) + wire }
            }
        }
        advanceUntilIdle()

        // Every toggle survived — no interleaved read-modify-write lost one.
        assertEquals(platforms.toSet(), source.enabledPlatforms.first())
    }

    @Test
    fun `transform sees null before anything is saved, then the saved set`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(dispatcher + Job()),
            produceFile = { File(tmp.root, "prefs2.preferences_pb") },
        )
        val source = PlatformPreferencesDataSource(ds)

        var sawNull = false
        source.updateEnabledPlatforms { saved ->
            sawNull = saved == null
            setOf("doordash")
        }
        var sawSaved: Set<String>? = null
        source.updateEnabledPlatforms { saved ->
            sawSaved = saved
            saved!! - "doordash"
        }
        advanceUntilIdle()

        assertEquals(true, sawNull)
        assertEquals(setOf("doordash"), sawSaved)
        assertEquals(emptySet<String>(), source.enabledPlatforms.first())
    }
}
