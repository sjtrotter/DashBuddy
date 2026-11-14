package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
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
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.abs

/**
 * #1622: "Delete my census data", separate from stopping (ADR-0011 §21). The census screen's button calls [delete]
 * through [deleteAsync]; the on-phone wipe (#1579, `PhoneWipe`) calls [requestHoldingLock] under the lock it keeps.
 *
 * Under the uploader lock (never mid-batch), it stores the request as pending, queues its own retry run on any
 * network ([CensusUploadScheduler.enqueueDeletion]) before the first attempt, cancels a waiting manual run and clears
 * what waits on the phone (the queued outlines and their in-flight batch, the test build's envelopes, the unsent daily
 * counts), as the server's client guide asks, then sends `DELETE /v1/installs/me/data` with the key of the one
 * enrolled install ID the request binds at its first attempt. Until the request is resolved (the server answered AND
 * the queue is cleared), every census run sends it before anything else and sends nothing else ([resumePending]). The
 * identity reset runs a fresh request first and resets only on its confirmation ([requestHoldingLock],
 * [CensusIdentityResetter]), so no identity with uploads is ever let go undeleted. A 401 never counts as deleted: the
 * server answers 401 to a bad signature as well as to an ID it does not know, so the request stays pending with its
 * ID. Consent is left as it is.
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
    private val appVersion: String,
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
        @Named("appVersionName") appVersion: String,
    ) : this(credentials, spool, envelopeSink, healthSink, preferences, scheduler, lock, apis, scope, appVersion,
        System::currentTimeMillis)

    private val running = MutableStateFlow(false)

    /** True while a request from this process is being made; the screen shows progress from it. */
    val inProgress: StateFlow<Boolean> = running.asStateFlow()

    /** The UI entry point: runs on the application scope, so leaving the screen cannot cancel it half-done. */
    fun deleteAsync(): Job = scope.launch { delete() }

    /**
     * App start: an unresolved request (one a process death interrupted, or one still waiting) gets its retry run
     * queued again. Never throws.
     */
    fun reconcile(): Job = scope.launch {
        try {
            if (preferences.censusDeletion.first()?.resolved == false) scheduler.enqueueDeletion()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census deletion reconcile failed")
        }
    }

    /** Records a new request, clears the phone's census queue and sends the deletion. Returns the stored outcome. */
    suspend fun delete(): CensusDeletion {
        running.value = true
        try {
            return lock.mutex.withLock { requestHoldingLock() }
        } finally {
            running.value = false
        }
    }

    /**
     * [delete] for a caller that already holds [CensusUploadLock] (the identity reset). Never throws for a failed store
     * write: what was saved stands, and the queued run resumes from it.
     */
    internal suspend fun requestHoldingLock(): CensusDeletion = withContext(NonCancellable) {
        val request = CensusDeletion(now(), State.PENDING)
        preferences.setCensusDeletion(request)
        // Queued before the first attempt, so a process death mid-request still leaves a retry.
        runCatching { scheduler.enqueueDeletion() }
        runCatching { scheduler.cancelManual() }
        Timber.tag(TAG).i("census deletion requested")
        try {
            attempt(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census deletion attempt failed")
            runCatching { preferences.censusDeletion.first() }.getOrNull() ?: request
        }
    }

    /**
     * For a caller that holds [CensusUploadLock] (a census run, the identity reset): clears and sends an unresolved
     * request. True when none is unresolved afterwards, so the caller may go on; false while one is, and the caller
     * must send nothing and keep the identity.
     */
    suspend fun resumePending(): Boolean {
        val stored = preferences.censusDeletion.first() ?: return true
        if (stored.resolved) return true
        return withContext(NonCancellable) { attempt(stored) }.resolved
    }

    private suspend fun attempt(request: CensusDeletion): CensusDeletion {
        var current = request
        if (!current.localCleared) {
            try {
                spool.clear()
                envelopeSink.invalidate()
                healthSink.reset()
                current = current.copy(localCleared = true)
                preferences.setCensusDeletion(current)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Uploads stay blocked (not resolved) and the next attempt clears again; the request still goes.
                Timber.tag(TAG).w("census deletion local clear failed")
            }
        }
        return if (current.pending) send(current) else current
    }

    /**
     * Binds the request to the current enrolled install ID at its first attempt and deletes under it with its key. The
     * request's state is saved before anything else happens to the identity.
     */
    private suspend fun send(request: CensusDeletion): CensusDeletion {
        var current = request
        val held = try {
            credentials.current()
        } catch (_: CensusCredentialStore.Unusable) {
            return finish(current.copy(state = State.NOT_POSSIBLE, waiting = null))
        } catch (_: CensusCredentialStore.Transient) {
            Timber.tag(TAG).i("census deletion pending cause=keystore")
            return finish(current.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
        }
        if (current.installId == null) {
            // No enrolled census ID (never enrolled, or enrolment unconfirmed): nothing is sent. A fact about the
            // request, not about the server.
            if (held == null) return finish(current.copy(state = State.NOTHING_SENT, waiting = null))
            current = finish(current.copy(installId = held.installId))
        }
        val credential = held?.takeIf { it.installId == current.installId } ?: run {
            // The ID the request was made for is gone (a revoke elsewhere, or a lost Keystore key).
            Timber.tag(TAG).w("census deletion: its identity is gone")
            return finish(current.copy(state = State.NOT_POSSIBLE, waiting = null))
        }
        val api = apis.create(preferences.censusBaseUrl.first())
        return when (val result = deleteWithRecovery(api, credential)) {
            is DeleteResult.Deleted -> {
                Timber.tag(TAG).i("census deleted rows=%d", result.rows)
                finish(current.copy(state = State.DELETED, rows = result.rows, deletedDay = result.deletedDay, waiting = null))
            }
            is DeleteResult.Unauthorized -> {
                // A bad signature, a skewed clock or an ID the server no longer knows all answer 401: never conclude.
                Timber.tag(TAG).w("census deletion pending cause=unauthorized skew=%s", clockSkew(result.serverDateMillis))
                finish(current.copy(waiting = CensusDeletion.Waiting.UNCONFIRMED))
            }
            DeleteResult.Revoked -> {
                // As the worker's revoke: the outcome is saved, the census switched off, then the identity forgotten.
                // The server deletes a revoked install's sightings itself on revocation.
                val done = finish(current.copy(state = State.NOT_POSSIBLE, waiting = null))
                preferences.setCensusUploadEnabled(false)
                envelopeSink.invalidate()
                preferences.setCensusShareCaptures(false)
                credentials.wipe()
                Timber.tag(TAG).w("census deletion: revoked")
                done
            }
            is DeleteResult.NotAvailable -> {
                Timber.tag(TAG).i("census deletion pending cause=not_available status=%d", result.status)
                finish(current.copy(waiting = CensusDeletion.Waiting.NOT_YET))
            }
            is DeleteResult.RateLimited, is DeleteResult.ServerUnavailable, is DeleteResult.TransportFailure -> {
                Timber.tag(TAG).i("census deletion pending cause=%s", result.javaClass.simpleName)
                finish(current.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
            }
        }
    }

    /**
     * The DELETE, and on a 401 the clock does not explain, one recovery: enrol the SAME ID with the SAME key (idempotent
     * on the server; after a restore that erased the install it re-creates it), then the DELETE once more. Only the
     * DELETE's own 200 resolves anything; an enrolment never counts as a deletion. Bounded by the caller's runs: once
     * per attempt, and attempts back off (WorkManager, from one minute) or wait for the next daily run.
     */
    private suspend fun deleteWithRecovery(api: CensusTransport, credential: CensusCredentialStore.Credential): DeleteResult {
        val first = delete(api, credential)
        if (first !is DeleteResult.Unauthorized || clockSkew(first.serverDateMillis)) return first
        val declared = CensusEnrolSchemas.declared(api.policyOrNull())
        if (declared.isEmpty()) return first
        val enrolled = try {
            api.enrol(credential.bearer(), appVersion, declared)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (enrolled !is EnrolResult.Enrolled) {
            Timber.tag(TAG).i("census deletion recovery enrol=%s", enrolled?.javaClass?.simpleName ?: "Exception")
            return first
        }
        Timber.tag(TAG).i("census deletion recovery enrolled the same identity; deleting again")
        return delete(api, credential)
    }

    private suspend fun delete(api: CensusTransport, credential: CensusCredentialStore.Credential): DeleteResult = try {
        api.deleteData(credential.bearer())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        DeleteResult.TransportFailure("Exception")
    }

    private suspend fun CensusTransport.policyOrNull(): JsonObject? = try {
        (policy() as? PolicyResult.Available)?.policy
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * For the worker, after an enrolment: a "nothing sent" result described the phone before it had a census ID, so it
     * is cleared once it has one again.
     */
    suspend fun onEnrolled() {
        if (preferences.censusDeletion.first()?.state == State.NOTHING_SENT) preferences.clearCensusDeletion()
    }

    private suspend fun finish(value: CensusDeletion): CensusDeletion = value.also { preferences.setCensusDeletion(it) }

    private fun clockSkew(serverDateMillis: Long?): Boolean =
        serverDateMillis != null && abs(serverDateMillis / 1000 - now() / 1000) > 300

    private companion object {
        const val TAG = "Census"
    }
}
