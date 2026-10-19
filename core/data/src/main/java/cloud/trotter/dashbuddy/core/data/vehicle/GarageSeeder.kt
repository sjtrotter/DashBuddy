package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import cloud.trotter.dashbuddy.core.datastore.settings.AppPreferencesDataSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Imports the saved wizard car once. The completion flag is written only after the garage write. */
@Singleton
class GarageSeeder @Inject constructor(
    private val garage: GarageRepository,
    private val preferences: AppPreferencesDataSource,
    private val appState: AppStateDataSource,
) {
    private val mutex = Mutex()

    suspend fun seed() = mutex.withLock {
        try {
            if (appState.garageSeeded.first()) return@withLock
            val profile = preferences.legacyVehicleProfile.first()
            if (profile != null) {
                garage.seedFrom(profile, System.currentTimeMillis())
            }
            appState.setGarageSeeded()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Keep garage details, preference values, and exception messages out of logs.
            Timber.tag("Garage").w("Garage seed failed: 1")
        }
    }
}
