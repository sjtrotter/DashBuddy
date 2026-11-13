package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusLastRun
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.di.ApplicationScope
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
 * the skeleton spool, invalidates the envelope sink, then clears the health ledger (a failure leaves the identity
 * in place), wipes the sealed credential, and records the reset as the last run in one DataStore edit.
 * The next run mints + enrols a fresh install id. The old server-side install is orphaned, not withdrawn.
 * Consent is left as it is; capture sharing is switched off — the new install must be trusted again.
 * A server-imposed deferral deadline is left as it is. [resetAsync] runs on the application scope so leaving the
 * screen cannot cancel it half-done.
 *
 * #1622: an unresolved "Delete my census data" request is bound to this identity, so the reset sends it first and is
 * refused (recorded as `failure`, the identity kept) while it stays unresolved.
 */
@Singleton
class CensusIdentityResetter internal constructor(
    private val credentials: CensusCredentialStore,
    private val spool: CensusSpool,
    private val preferences: DevSettingsRepository,
    private val scheduler: CensusUploadScheduler,
    private val lock: CensusUploadLock,
    private val scope: CoroutineScope,
    private val healthSink: PersistentHealthSink,
    private val now: () -> Long,
    private val envelopeSink: CensusEnvelopeSink,
    private val deletion: CensusDataDeletion,
) {
    @Inject constructor(
        credentials: CensusCredentialStore,
        spool: CensusSpool,
        preferences: DevSettingsRepository,
        scheduler: CensusUploadScheduler,
        lock: CensusUploadLock,
        @ApplicationScope scope: CoroutineScope,
        healthSink: PersistentHealthSink,
        envelopeSink: CensusEnvelopeSink,
        deletion: CensusDataDeletion,
    ) : this(credentials, spool, preferences, scheduler, lock, scope, healthSink, System::currentTimeMillis, envelopeSink, deletion)

    /** The UI entry point: never cancelled by the caller's lifecycle. */
    fun resetAsync(): Job = scope.launch { reset() }

    /**
     * Never throws: a mutation failure is logged and recorded as `failure` INSIDE the locked section, so a second
     * reset that succeeds meanwhile cannot have its `reset` record overwritten by the first one's late failure.
     * Returns whether the identity was reset.
     */
    suspend fun reset(): Boolean {
        val done = lock.mutex.withLock {
            withContext(NonCancellable) {
                try {
                    if (!deletion.resumePending()) {
                        Timber.tag(TAG).w("census identity reset refused: deletion pending")
                        runCatching { preferences.setCensusLastRun(CensusLastRun(now(), CensusRunOutcome.FAILURE)) }
                        return@withContext false
                    }
                    spool.clear()
                    envelopeSink.invalidate()
                    preferences.setCensusShareCaptures(false)
                    healthSink.reset()
                    credentials.wipe()
                    preferences.recordCensusReset(CensusLastRun(now(), CensusRunOutcome.RESET))
                    Timber.tag(TAG).i("census identity reset")
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Timber.tag(TAG).w("census identity reset failed")
                    runCatching { preferences.setCensusLastRun(CensusLastRun(now(), CensusRunOutcome.FAILURE)) }
                    false
                }
            }
        }
        if (done && preferences.censusUploadEnabled.first()) {
            scheduler.enqueueNow(preferences.censusChoices.first().wifiOnly, replaceQueued = true)
        }
        return done
    }

    private companion object {
        const val TAG = "Census"
    }
}
