package cloud.trotter.log.gig.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.datastore.settings.SimplePrefs
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.datastore.vehicle.VehicleDataSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OdometerPromptsPreferenceTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `odometer prompts default true and round trip through the repository mirror`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("app.preferences_pb") }))
        val repo = AppPreferencesRepository(source, GarageRepository(VehicleDataSource(
            PreferenceDataStoreFactory.create(scope = backgroundScope,
                produceFile = { tmp.newFile("vehicles.preferences_pb") }))))
        assertTrue(repo.odometerPrompts.first())
        repo.setOdometerPrompts(false)
        assertFalse(source.read(SimplePrefs.odometerPrompts).first())
        assertFalse(repo.odometerPrompts.first())
        repo.setOdometerPrompts(true)
        assertTrue(repo.odometerPrompts.first())
        repo.clearPreferences()
        assertTrue(repo.odometerPrompts.first())
    }
}
