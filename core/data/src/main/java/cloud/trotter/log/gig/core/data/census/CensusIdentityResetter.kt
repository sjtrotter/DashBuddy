package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusDeletion
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
 * Developer action (#1185), test build only: forget this phone's census identity. Under the uploader lock (never
 * mid-batch) it clears the skeleton spool, invalidates the envelope sink, then clears the health ledger (a failure
 * leaves the identity in place), wipes the sealed credential, and records the reset as the last run in one DataStore
 * edit. The next run mints + enrols a fresh install id.
 *
 * #1622: an ENROLLED identity may have uploads on the server, so it is reset only after a fresh "Delete my census data"
 * request for it is confirmed by the server (and the phone's queue cleared); anything short of that (offline, the
 * server not yet taking deletions, a 401 after the same-key recovery) refuses the reset, keeps the identity and
 * records `reset_refused`. No identity with uploads is ever let go undeleted, so the phone keeps no old keys. A pending
 * (never enrolled) identity uploaded nothing and resets freely; so does one whose key is lost, which can sign nothing.
 * Consent is left as it is; capture sharing is switched off — the new install must be trusted again.
 * A server-imposed deferral deadline is left as it is. [resetAsync] runs on the application scope so leaving the
 * screen cannot cancel it half-done.
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
                    val enrolled = try {
                        credentials.current()
                    } catch (_: CensusCredentialStore.Unusable) {
                        null // A lost key signs nothing: there is nothing this phone could delete under it.
                    }
                    if (enrolled != null) {
                        val request = deletion.requestHoldingLock()
                        val confirmed = request.state == CensusDeletion.State.DELETED && request.resolved &&
                            request.installId == enrolled.installId
                        if (!confirmed) {
                            Timber.tag(TAG).w("census identity reset refused: deletion not confirmed")
                            runCatching { preferences.setCensusLastRun(CensusLastRun(now(), CensusRunOutcome.RESET_REFUSED)) }
                            return@withContext false
                        }
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
