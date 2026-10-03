package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Developer action (#1185): forget this phone's census identity. Under the uploader lock (never mid-batch) it clears
 * the spool FIRST (a failed clear leaves the identity in place), wipes the sealed credential, and records the reset
 * as the last run in one DataStore edit; the next run mints + enrols a fresh install id, which the server must trust
 * again. The old server-side install is orphaned, not withdrawn. Consent and a server-imposed deferral deadline are
 * left as they are. [resetAsync] runs on the application scope so leaving the screen cannot cancel it half-done.
 */
@Singleton
class CensusIdentityResetter internal constructor(
    private val credentials: CensusCredentialStore,
    private val spool: CensusSpool,
    private val preferences: DevSettingsRepository,
    private val scheduler: CensusUploadScheduler,
    private val lock: CensusUploadLock,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    @Inject constructor(
        credentials: CensusCredentialStore,
        spool: CensusSpool,
        preferences: DevSettingsRepository,
        scheduler: CensusUploadScheduler,
        lock: CensusUploadLock,
        @ApplicationScope scope: CoroutineScope,
    ) : this(credentials, spool, preferences, scheduler, lock, scope, System::currentTimeMillis)

    /** The UI entry point: never throws, never cancelled by the caller's lifecycle; a failure is recorded, not crashed. */
    fun resetAsync(): Job = scope.launch {
        try {
            reset()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census identity reset failed")
            runCatching { preferences.setCensusLastRun(CensusLastRun(now(), CensusRunOutcome.FAILURE)) }
        }
    }

    suspend fun reset() {
        lock.mutex.withLock {
            withContext(NonCancellable) {
                spool.clear()
                credentials.wipe()
                preferences.recordCensusReset(CensusLastRun(now(), CensusRunOutcome.RESET))
                Timber.tag(TAG).i("census identity reset")
            }
        }
        if (preferences.censusUploadEnabled.first()) scheduler.enqueueNow(replaceQueued = true)
    }

    private companion object {
        const val TAG = "Census"
    }
}
