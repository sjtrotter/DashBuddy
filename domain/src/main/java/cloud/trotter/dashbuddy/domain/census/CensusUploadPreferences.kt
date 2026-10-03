package cloud.trotter.dashbuddy.domain.census

import kotlinx.coroutines.flow.Flow

/** Read-only consent boundary; default consent is off. */
interface CensusUploadPreferences {
    val enabled: Flow<Boolean>
    val baseUrl: Flow<String>
}

/** The app owns WorkManager; data and feature modules only request a run. */
interface CensusUploadScheduler {
    fun enqueueNow()
    fun deferUntil(epochMillis: Long)
}
