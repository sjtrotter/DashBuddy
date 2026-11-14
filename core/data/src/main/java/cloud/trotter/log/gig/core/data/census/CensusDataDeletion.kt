package cloud.trotter.log.gig.core.data.census

import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.network.census.CensusApiFactory
import cloud.trotter.log.gig.core.network.census.CensusTransport
import cloud.trotter.log.gig.core.network.census.DeleteResult
import cloud.trotter.log.gig.core.network.census.EnrolResult
import cloud.trotter.log.gig.core.network.census.PolicyResult
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.CensusDeletion.Outcome
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
            return lock.mutex.withLock {
                withContext(NonCancellable) {
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
                        // A store write failed mid-request: what was saved stands, and the queued run resumes from it.
                        Timber.tag(TAG).w("census deletion attempt failed")
                        runCatching { preferences.censusDeletion.first() }.getOrNull() ?: request
                    }
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
        forgetConfirmedKeys(stored)
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
     * Binds the request to its install IDs at the first attempt (the retired ones, then the current enrolled one), then
     * deletes under each pending one with its own key. Each confirmation is saved BEFORE its retired key is forgotten,
     * and forgetting is an idempotent cleanup ([forgetConfirmedKeys]) re-run on every load, so no interruption can
     * leave a pending ID without its key. An ID whose key is gone is not possible on its own; the others go on.
     */
    private suspend fun send(request: CensusDeletion): CensusDeletion {
        var current = request
        if (current.targets == null) {
            val ids = try {
                credentials.retiredIds() + listOfNotNull(credentials.current()?.installId)
            } catch (_: CensusCredentialStore.Unusable) {
                return finish(current.copy(state = State.NOT_POSSIBLE, targets = emptyList(), waiting = null))
            } catch (_: CensusCredentialStore.Transient) {
                Timber.tag(TAG).i("census deletion pending cause=keystore")
                return finish(current.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
            }
            // No census ID at all (never enrolled, or enrolment unconfirmed): nothing is sent. This is a fact about
            // the request, not about the server.
            if (ids.isEmpty()) return finish(current.copy(state = State.NOTHING_SENT, targets = emptyList(), waiting = null))
            current = finish(current.copy(targets = ids.distinct().map { CensusDeletion.Target(it) }))
        }
        val api = apis.create(preferences.censusBaseUrl.first())
        for (target in requireNotNull(current.targets).filter { it.outcome == Outcome.PENDING }) {
            val id = target.installId
            var retired = false
            val credential = try {
                credentials.current()?.takeIf { it.installId == id } ?: credentials.retired(id)?.also { retired = true }
            } catch (_: CensusCredentialStore.Unusable) {
                null
            } catch (_: CensusCredentialStore.Transient) {
                Timber.tag(TAG).i("census deletion pending cause=keystore")
                return finish(current.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
            }
            if (credential == null) {
                // This ID's key is gone (a revoke elsewhere, or a lost Keystore key): only this ID is not possible.
                Timber.tag(TAG).w("census deletion: an identity's key is gone")
                current = finish(current.with(target.copy(outcome = Outcome.NOT_POSSIBLE)))
                continue
            }
            when (val result = deleteWithRecovery(api, credential)) {
                is DeleteResult.Deleted -> {
                    Timber.tag(TAG).i("census deleted rows=%d", result.rows)
                    // The confirmation is saved first; only then is the retired key forgotten.
                    current = finish(current.with(target.copy(outcome = Outcome.DELETED, rows = result.rows, day = result.deletedDay)))
                    if (retired) forgetRetiredQuietly(id)
                }
                is DeleteResult.Unauthorized -> {
                    // A bad signature, a skewed clock or an ID the server no longer knows all answer 401: never conclude.
                    Timber.tag(TAG).w("census deletion pending cause=unauthorized skew=%s", clockSkew(result.serverDateMillis))
                    return finish(current.copy(waiting = CensusDeletion.Waiting.UNCONFIRMED))
                }
                DeleteResult.Revoked -> {
                    // As the worker's revoke: switch off first, then forget the identity. Other IDs go on.
                    preferences.setCensusUploadEnabled(false)
                    envelopeSink.invalidate()
                    preferences.setCensusShareCaptures(false)
                    current = finish(current.with(target.copy(outcome = Outcome.NOT_POSSIBLE)))
                    if (retired) forgetRetiredQuietly(id) else credentials.wipe()
                    Timber.tag(TAG).w("census deletion: revoked")
                }
                is DeleteResult.NotAvailable -> {
                    Timber.tag(TAG).i("census deletion pending cause=not_available status=%d", result.status)
                    return finish(current.copy(waiting = CensusDeletion.Waiting.NOT_YET))
                }
                is DeleteResult.RateLimited, is DeleteResult.ServerUnavailable, is DeleteResult.TransportFailure -> {
                    Timber.tag(TAG).i("census deletion pending cause=%s", result.javaClass.simpleName)
                    return finish(current.copy(waiting = CensusDeletion.Waiting.UNREACHABLE))
                }
            }
        }
        val anyDeleted = current.targets.orEmpty().any { it.outcome == Outcome.DELETED }
        return finish(current.copy(state = if (anyDeleted) State.DELETED else State.NOT_POSSIBLE, waiting = null))
    }

    private fun CensusDeletion.with(target: CensusDeletion.Target): CensusDeletion =
        copy(targets = targets.orEmpty().map { if (it.installId == target.installId) target else it })

    /**
     * The idempotent half of a confirmation: a retired key whose ID the server confirmed is forgotten. Re-run on every
     * load, so a forget that failed (or a process death after the confirmation was saved) is finished later. Never throws.
     */
    private suspend fun forgetConfirmedKeys(stored: CensusDeletion) {
        val confirmed = stored.targets.orEmpty().filter { it.outcome == Outcome.DELETED }.map { it.installId }
        if (confirmed.isEmpty()) return
        val retired = runCatching { credentials.retiredIds() }.getOrNull() ?: return
        confirmed.filter { it in retired }.forEach { forgetRetiredQuietly(it) }
    }

    private suspend fun forgetRetiredQuietly(id: String) {
        try {
            credentials.forgetRetired(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Timber.tag(TAG).w("census deletion: a confirmed identity's key could not be forgotten yet")
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
