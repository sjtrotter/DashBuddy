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
 * Under the uploader lock (never mid-batch), it stores the request as pending, queues its own retry run on any
 * network ([CensusUploadScheduler.enqueueDeletion]) before the first attempt, cancels a waiting manual run and clears
 * what waits on the phone (the queued outlines and their in-flight batch, the test build's envelopes, the unsent daily
 * counts), as the server's client guide asks, then sends `DELETE /v1/installs/me/data` with the credential of the
 * install ID the request is bound to. Until the request is resolved (the server answered AND the queue is cleared),
 * every census run sends it before anything else and sends nothing else ([resumePending]), and the identity cannot be
 * reset ([CensusIdentityResetter]). A 401 never counts as "nothing held": the server answers 401 to a bad signature as
 * well as to an ID it does not know, so the request stays pending with its ID. Consent is left as it is.
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
            return lock.mutex.withLock {
                withContext(NonCancellable) {
                    val request = CensusDeletion(now(), State.PENDING)
                    preferences.setCensusDeletion(request)
                    // Queued before the first attempt, so a process death mid-request still leaves a retry.
                    runCatching { scheduler.enqueueDeletion() }
                    runCatching { scheduler.cancelManual() }
                    Timber.tag(TAG).i("census deletion requested")
                    attempt(request)
                }
            }
        } finally {
            running.value = false
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

    private suspend fun send(request: CensusDeletion): CensusDeletion {
        val held = try {
            credentials.current()
        } catch (_: CensusCredentialStore.Unusable) {
            return finish(request.copy(state = State.NOT_POSSIBLE, waiting = null))
        } catch (_: CensusCredentialStore.Transient) {
            Timber.tag(TAG).i("census deletion pending cause=keystore")
            return finish(request.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
        }
        val bound = request.installId
        val credential = when {
            // Never enrolled (or enrolment unconfirmed): nothing was uploaded under any ID, so no request is sent.
            bound == null && held == null -> return finish(request.copy(state = State.NOTHING_HELD, waiting = null))
            // The ID the request was made for is gone (only a revoke elsewhere or a lost key can do that).
            bound != null && held?.installId != bound -> {
                Timber.tag(TAG).w("census deletion: its identity is gone")
                return finish(request.copy(state = State.NOT_POSSIBLE, waiting = null))
            }
            else -> requireNotNull(held)
        }
        val target = if (bound == null) finish(request.copy(installId = credential.installId)) else request
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
                finish(target.copy(state = State.DELETED, rows = result.rows, deletedDay = result.deletedDay, waiting = null))
            }
            is DeleteResult.Unauthorized -> {
                // A bad signature, a skewed clock or an ID the server no longer knows all answer 401: never conclude.
                Timber.tag(TAG).w("census deletion pending cause=unauthorized skew=%s", clockSkew(result.serverDateMillis))
                finish(target.copy(waiting = CensusDeletion.Waiting.UNCONFIRMED))
            }
            DeleteResult.Revoked -> {
                // As the worker's revoke: switch off first, then forget the identity.
                preferences.setCensusUploadEnabled(false)
                envelopeSink.invalidate()
                preferences.setCensusShareCaptures(false)
                credentials.wipe()
                Timber.tag(TAG).w("census deletion: revoked")
                finish(target.copy(state = State.NOT_POSSIBLE, waiting = null))
            }
            is DeleteResult.NotAvailable -> {
                Timber.tag(TAG).i("census deletion pending cause=not_available status=%d", result.status)
                finish(target.copy(waiting = CensusDeletion.Waiting.NOT_YET))
            }
            is DeleteResult.RateLimited, is DeleteResult.ServerUnavailable, is DeleteResult.TransportFailure -> {
                Timber.tag(TAG).i("census deletion pending cause=%s", result.javaClass.simpleName)
                finish(target.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
            }
        }
    }

    private suspend fun finish(value: CensusDeletion): CensusDeletion = value.also { preferences.setCensusDeletion(it) }

    private fun clockSkew(serverDateMillis: Long?): Boolean =
        serverDateMillis != null && abs(serverDateMillis / 1000 - now() / 1000) > 300

    private companion object {
        const val TAG = "Census"
    }
}
