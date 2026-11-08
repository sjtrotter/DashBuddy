package cloud.trotter.log.gig.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.datastore.settings.AppPreferencesDataSource
import cloud.trotter.log.gig.core.datastore.vehicle.VehicleDataSource
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindowSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1515: Recap's stored selection is a week; anything else decodes to this week. */
class RecapWeekPreferenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a stored selection that is not a week decodes to this week`() = runTest {
        val source = AppPreferencesDataSource(PreferenceDataStoreFactory.create(scope = backgroundScope,
            produceFile = { tmp.newFile("app.preferences_pb") }))
        val repo = AppPreferencesRepository(source, GarageRepository(VehicleDataSource(
            PreferenceDataStoreFactory.create(scope = backgroundScope,
                produceFile = { tmp.newFile("vehicles.preferences_pb") }))))

        assertEquals(AnalyticsWindowSelection.DEFAULT, repo.analyticsWindow.first())
        for (stored in listOf("DAY", "MONTH", "LIFETIME", "CUSTOM", "nonsense")) {
            source.setAnalyticsWindow(stored, -2)
            assertEquals(stored, AnalyticsWindowSelection.DEFAULT, repo.analyticsWindow.first())
        }
        source.setAnalyticsWindow("WEEK", 3)
        assertEquals("a future week clamps to this week", AnalyticsWindowSelection.DEFAULT, repo.analyticsWindow.first())

        repo.setAnalyticsWindow(AnalyticsWindowSelection(-2))
        assertEquals(AnalyticsWindowSelection(-2), repo.analyticsWindow.first())
    }
}
