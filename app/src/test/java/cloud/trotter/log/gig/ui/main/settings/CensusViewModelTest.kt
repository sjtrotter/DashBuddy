package cloud.trotter.log.gig.ui.main.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.feature.settings.CensusViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** #1621 (Sol round 1 S2): quick checkbox changes on the census screen never overwrite each other. */
@OptIn(ExperimentalCoroutinesApi::class)
class CensusViewModelTest {

    /** A store that takes 100 ms per write and serializes writes, as DataStore does. */
    private class SlowPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = lock.withLock {
            delay(100)
            transform(data.value).also { data.value = it }
        }
    }

    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun `two quick platform changes both land while the first write is still in flight`() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(main)
        val repo = DevSettingsRepository(DevSettingsDataSource(SlowPreferences()), true, main, "test")
        val vm = CensusViewModel(repo, readsUnboundPlatforms = true)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        vm.setPlatform(Platform.DoorDash, false)
        vm.setPlatform(Platform.Uber, false)
        advanceUntilIdle()

        assertEquals(
            Platform.readable(includeUnbound = true).toSet() - Platform.DoorDash - Platform.Uber,
            repo.censusChoices.first().platforms,
        )

        vm.setPlatform(Platform.Uber, true)
        vm.setPlatform(Platform.Instacart, false)
        advanceUntilIdle()
        assertEquals(setOf(Platform.Uber, Platform.WalmartSpark), repo.censusChoices.first().platforms)
    }
}
