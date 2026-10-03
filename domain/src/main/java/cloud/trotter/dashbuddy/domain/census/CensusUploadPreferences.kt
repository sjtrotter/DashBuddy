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
    /** A spool that just became non-empty uploads within ~5 minutes without any manual step (dev ask, 2026-10-03); repeated calls keep the first deadline. */
    fun enqueueSoon()
    fun deferUntil(epochMillis: Long)
}
