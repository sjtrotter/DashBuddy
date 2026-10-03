package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer action (#1185): forget this phone's census identity. Wipes the sealed credential, the spool and the
 * run state under the uploader lock (never mid-batch); the next run mints + enrols a fresh install id, which the
 * server must trust again. The old server-side install is orphaned, not withdrawn. Consent is left as it is.
 */
@Singleton
class CensusIdentityResetter internal constructor(
    private val credentials: CensusCredentialStore,
    private val spool: CensusSpool,
    private val preferences: DevSettingsRepository,
    private val scheduler: CensusUploadScheduler,
    private val lock: CensusUploadLock,
    private val now: () -> Long,
) {
    @Inject constructor(
        credentials: CensusCredentialStore,
        spool: CensusSpool,
        preferences: DevSettingsRepository,
        scheduler: CensusUploadScheduler,
        lock: CensusUploadLock,
    ) : this(credentials, spool, preferences, scheduler, lock, System::currentTimeMillis)

    suspend fun reset() {
        lock.mutex.withLock {
            credentials.wipe()
            spool.clear()
            preferences.clearCensusRunState()
            preferences.setCensusLastRun(CensusLastRun(now(), CensusRunOutcome.RESET))
            Timber.tag(TAG).w("census identity reset")
        }
        if (preferences.censusUploadEnabled.first()) scheduler.enqueueNow()
    }

    private companion object {
        const val TAG = "Census"
    }
}
