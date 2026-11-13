package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.CensusDeletion.State
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import cloud.trotter.log.gig.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * #1622: "Delete my census data", separate from stopping (ADR-0011 §21). [delete] is the one entry point: the census
 * screen's button calls it through [deleteAsync], and the on-phone wipe (#1579) calls it too.
 *
 * Under the uploader lock (never mid-batch), it first stores the request as pending, then cancels a waiting manual
 * run and clears what waits on the phone (the queued outlines and their in-flight batch, the test build's envelopes,
 * the unsent daily counts), as the server's client guide asks, then sends `DELETE /v1/installs/me/data`. While the
 * request is pending, every census run sends it before anything else and sends nothing else until it resolves
 * ([resumePending]); a request that could not be sent is retried by its own run on any network
 * ([CensusUploadScheduler.enqueueDeletion]). Consent is left as it is: stopping is the switch.
 */
@Singleton
class CensusDataDeletion internal constructor(
    private val credentials: CensusCredentialStore,
    private val spool: CensusSpool,
    private val envelopeSink: CensusEnvelopeSink,
    private val healthSink: PersistentHealthSink,
    private val preferences: DevSettingsRepository,
    private val scheduler: CensusUploadScheduler,
    private val lock: CensusUploadLock,
    private val apis: CensusApiFactory,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    @Inject constructor(
        credentials: CensusCredentialStore,
        spool: CensusSpool,
        envelopeSink: CensusEnvelopeSink,
        healthSink: PersistentHealthSink,
        preferences: DevSettingsRepository,
        scheduler: CensusUploadScheduler,
        lock: CensusUploadLock,
        apis: CensusApiFactory,
        @ApplicationScope scope: CoroutineScope,
    ) : this(credentials, spool, envelopeSink, healthSink, preferences, scheduler, lock, apis, scope, System::currentTimeMillis)

    private val running = MutableStateFlow(false)

    /** True while a request from this process is being made; the screen shows progress from it. */
    val inProgress: StateFlow<Boolean> = running.asStateFlow()

    /** The UI entry point: runs on the application scope, so leaving the screen cannot cancel it half-done. */
    fun deleteAsync(): Job = scope.launch { delete() }

    /**
     * Records a new request, clears the phone's census queue and sends the deletion. Returns the stored outcome: a
     * request that could not be sent stays [State.PENDING] and its retry run is queued.
     */
    suspend fun delete(): CensusDeletion {
        running.value = true
        try {
            val result = lock.mutex.withLock {
                withContext(NonCancellable) {
                    val request = CensusDeletion(now(), State.PENDING)
                    preferences.setCensusDeletion(request)
                    Timber.tag(TAG).i("census deletion requested")
                    runCatching { scheduler.cancelManual() }
                    attempt(request)
                }
            }
            if (result.pending) scheduler.enqueueDeletion()
            return result
        } finally {
            running.value = false
        }
    }

    /**
     * For a census run that holds [CensusUploadLock]: sends a pending request. True when no request is pending
     * afterwards, so the run may go on; false when it is still pending, and the run must send nothing.
     */
    suspend fun resumePending(): Boolean {
        val stored = preferences.censusDeletion.first() ?: return true
        if (!stored.pending) return true
        return !withContext(NonCancellable) { attempt(stored) }.pending
    }

    private suspend fun attempt(request: CensusDeletion): CensusDeletion {
        var current = request
        if (!current.localCleared) {
            try {
                spool.clear()
                envelopeSink.invalidate()
                healthSink.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Send the request anyway; the next attempt clears the queue again before it sends.
                Timber.tag(TAG).w("census deletion local clear failed")
                return send(current)
            }
            current = current.copy(localCleared = true)
            preferences.setCensusDeletion(current)
        }
        return send(current)
    }

    private suspend fun send(request: CensusDeletion): CensusDeletion {
        val enrolled = try {
            credentials.current()
        } catch (_: CensusCredentialStore.Unusable) {
            return finish(request, State.NOT_POSSIBLE)
        } catch (_: CensusCredentialStore.Transient) {
            Timber.tag(TAG).i("census deletion pending cause=keystore")
            return request
        }
        // Never enrolled, or enrolment unconfirmed: the server took no data under this ID.
        val credential = enrolled ?: return finish(request, State.NOTHING_HELD)
        val result = try {
            apis.create(preferences.censusBaseUrl.first()).deleteData(credential.bearer())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DeleteResult.TransportFailure("Exception")
        }
        return when (result) {
            is DeleteResult.Deleted -> {
                Timber.tag(TAG).i("census deleted rows=%d", result.rows)
                finish(request, State.DELETED, result.rows, result.deletedDay)
            }
            is DeleteResult.Unauthorized -> if (clockSkew(result.serverDateMillis)) {
                Timber.tag(TAG).i("census deletion pending cause=clock_skew")
                request
            } else {
                // The server no longer knows this ID (a restore erased it whole, census#88): nothing is tied to it.
                // The next run enrols a fresh one, consent kept.
                forgetIdentity()
                Timber.tag(TAG).w("census deletion: identity unknown to server")
                finish(request, State.NOTHING_HELD)
            }
            DeleteResult.Revoked -> {
                // As the worker's revoke: switch off first, then forget the identity.
                preferences.setCensusUploadEnabled(false)
                forgetIdentity()
                Timber.tag(TAG).w("census deletion: revoked")
                finish(request, State.NOT_POSSIBLE)
            }
            is DeleteResult.RateLimited, is DeleteResult.ServerUnavailable, is DeleteResult.TransportFailure -> {
                Timber.tag(TAG).i("census deletion pending cause=%s", result.javaClass.simpleName)
                request
            }
        }
    }

    private suspend fun forgetIdentity() {
        envelopeSink.invalidate()
        preferences.setCensusShareCaptures(false)
        credentials.wipe()
    }

    private suspend fun finish(request: CensusDeletion, state: State, rows: Int? = null, day: String? = null): CensusDeletion =
        request.copy(state = state, rows = rows, deletedDay = day).also { preferences.setCensusDeletion(it) }

    private fun clockSkew(serverDateMillis: Long?): Boolean =
        serverDateMillis != null && abs(serverDateMillis / 1000 - now() / 1000) > 300

    private companion object {
        const val TAG = "Census"
    }
}
