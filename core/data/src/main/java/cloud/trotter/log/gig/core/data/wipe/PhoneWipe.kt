package cloud.trotter.log.gig.core.data.wipe

import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Where the wipe is (#1579). The settings dialog renders it; [PhoneWipe] owns it. */
sealed interface WipeProgress {
    data object Idle : WipeProgress

    /** A census run holds the upload lock; nothing is deleted until it finishes. The dasher can cancel. */
    data object WaitingForUpload : WipeProgress

    /** The census deletion request is sent; the dialog waits at most [PhoneWipe.CENSUS_WAIT_MILLIS] for its answer. */
    data object AskingCensus : WipeProgress

    /** Deleting. [censusConfirmed]: the census server confirmed its deletion first, and the screen says so. */
    data class Wiping(val censusConfirmed: Boolean) : WipeProgress

    /** The census request is not confirmed: the dasher picks Delete now or Wait. Nothing is deleted yet. */
    data class CensusNotConfirmed(val reason: Reason) : WipeProgress

    /** Android did not start the clear; nothing is deleted except the census key. */
    data object Failed : WipeProgress

    enum class Reason {
        /** No answer while the dialog waited; the request goes on, stored as pending with its retry queued. */
        NO_ANSWER,
        UNREACHABLE,

        /** The server answers 404 until its update (census#79). */
        NOT_YET,

        /** A 401 that the same-key recovery did not clear. */
        UNCONFIRMED,

        /** This phone's census ID can no longer sign a request (a lost key); waiting changes nothing. */
        NOT_POSSIBLE;

        val canWait: Boolean get() = this != NOT_POSSIBLE
    }
}

/**
 * #1579: Settings > Data & privacy > Delete everything on this phone. One coordinator coroutine per wipe, on the
 * application scope, owns the census upload lock for the whole flow and takes the dasher's choices as messages.
 *
 * - Nothing is deleted (the census key or the app's storage) unless the coordinator holds [CensusUploadLock]. A census
 *   run holding it is waited for, never cleared beside ([WipeProgress.WaitingForUpload], with Cancel).
 * - Under the lock it decides enrollment. An enrolled ID, or a credential store it cannot read, goes through #1622's
 *   [CensusDataDeletion.requestHoldingLock], which stores the request as pending and queues its retry run before any
 *   network attempt; [CENSUS_WAIT_MILLIS] bounds only how long the dialog waits for the answer. A confirmation (or a
 *   request that found no enrolled ID) goes on to the wipe; anything else deletes nothing and offers Delete now or
 *   Wait. A late answer still lands: a confirmation then goes on to the wipe, a new reason replaces the shown one.
 *   Wait closes the dialog, and the coordinator releases the lock once the request finishes.
 * - Once the wipe is committed (no census ID, a confirmation, or Delete now) the commitment is stored ([WipeIntent])
 *   before anything is deleted, and app start checks it before anything else runs ([resumeCommitted]).
 *
 * The wipe deletes the census Keystore key, then runs Android's own "Clear storage" ([AppStorageClearer]): Android stops
 * every gig.log process, service and scheduled job, then deletes all of the app's private storage (the database with
 * its WAL and SHM files and pre-migration snapshots, every DataStore file, logs, captures, census files, WorkManager's
 * database and the stored commitment) and its Keystore keys. Exports, gallery screenshots and Android's backup live
 * outside the app's storage and stay.
 */
@Singleton
class PhoneWipe @Inject constructor(
    private val credentials: CensusCredentialStore,
    private val deletion: CensusDataDeletion,
    private val lock: CensusUploadLock,
    private val sealer: KeystoreSealer,
    private val clearer: AppStorageClearer,
    private val intent: WipeIntent,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private enum class Decision { DELETE_NOW, LEAVE }

    private sealed interface CensusAnswer {
        data object Confirmed : CensusAnswer

        /** The request found no enrolled ID, so nothing was sent and nothing on the server is tied to this phone. */
        data object NothingSent : CensusAnswer
        data class NotConfirmed(val reason: WipeProgress.Reason) : CensusAnswer
    }

    private val state = MutableStateFlow<WipeProgress>(WipeProgress.Idle)
    val progress: StateFlow<WipeProgress> = state.asStateFlow()

    /** The running coordinator's inbox; null while none runs. */
    private val inbox = AtomicReference<Channel<Decision>?>(null)

    /**
     * For the confirmation: whether the census server will be asked first. True for an enrolled ID and for a credential
     * store that cannot be read (unknown is never "not enrolled"). Reads no secret.
     */
    suspend fun censusEnrolled(): Boolean = readEnrolled() != false

    /** The confirmation's Delete everything. Ignored while a wipe runs. */
    fun start() {
        val decisions = Channel<Decision>(Channel.CONFLATED)
        if (state.value != WipeProgress.Idle || !inbox.compareAndSet(null, decisions)) return
        val owner = Any()
        scope.launch {
            try {
                coordinate(owner, decisions)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Timber.tag(TAG).w("phone wipe: stopped by an error; nothing more is deleted")
                state.value = WipeProgress.Failed
            } finally {
                inbox.compareAndSet(decisions, null)
                if (lock.mutex.holdsLock(owner)) lock.mutex.unlock(owner)
            }
        }
    }

    /** "Delete now" after an unconfirmed census request: the server keeps what it holds for this phone. */
    fun wipeNow() {
        if (state.value is WipeProgress.CensusNotConfirmed) inbox.get()?.trySend(Decision.DELETE_NOW)
    }

    /** Cancel, Wait or OK: closes the dialog and deletes nothing. A pending census request keeps retrying (#1622). */
    fun dismiss() {
        when (state.value) {
            WipeProgress.WaitingForUpload, is WipeProgress.CensusNotConfirmed -> inbox.get()?.trySend(Decision.LEAVE)
            WipeProgress.Failed -> state.compareAndSet(WipeProgress.Failed, WipeProgress.Idle)
            else -> Unit
        }
    }

    /**
     * App start, before sensing, workers or uploads: a wipe committed before the process died is finished now. Takes
     * the upload lock synchronously when it is free (at start nothing else holds it), so no census run goes first.
     * True when the wipe is under way and the caller must start nothing else; false when none was committed, or when
     * Android refused the clear (the commitment is dropped and the app starts as usual).
     */
    fun resumeCommitted(): Boolean {
        val committed = try {
            intent.isCommitted()
        } catch (_: Exception) {
            false
        }
        if (!committed) return false
        Timber.tag(TAG).i("phone wipe: finishing a committed wipe at start")
        state.value = WipeProgress.Wiping(censusConfirmed = false)
        val owner = Any()
        if (lock.mutex.tryLock(owner)) {
            if (clearStorage()) return true
            lock.mutex.unlock(owner)
            return false
        }
        scope.launch {
            lock.mutex.lock(owner)
            if (!clearStorage()) lock.mutex.unlock(owner)
        }
        return true
    }

    private suspend fun coordinate(owner: Any, decisions: Channel<Decision>) {
        if (!acquire(owner, decisions)) {
            leave(decisions)
            return
        }
        val enrolled = readEnrolled()
        if (enrolled == false) return wipe(censusConfirmed = false)
        state.value = WipeProgress.AskingCensus
        Timber.tag(TAG).i("phone wipe: asking the census server first enrolled=%s", enrolled ?: "unknown")
        // Runs under this coordinator's lock; after Wait the lock is released once it finishes.
        val request = scope.async { deletion.requestHoldingLock() }
        var answer = withTimeoutOrNull(CENSUS_WAIT_MILLIS) { request.answer() }
            ?: CensusAnswer.NotConfirmed(WipeProgress.Reason.NO_ANSWER)
        while (true) {
            val current = when (val latest = answer) {
                CensusAnswer.Confirmed -> return wipe(censusConfirmed = true)
                CensusAnswer.NothingSent -> return wipe(censusConfirmed = false)
                is CensusAnswer.NotConfirmed -> latest
            }
            Timber.tag(TAG).i("phone wipe: census not confirmed reason=%s", current.reason.name.lowercase(Locale.ROOT))
            state.value = WipeProgress.CensusNotConfirmed(current.reason)
            val next = select<Any> {
                decisions.onReceive { it }
                if (request.isActive) request.onJoin { request.answer() }
            }
            when (next) {
                Decision.DELETE_NOW -> {
                    Timber.tag(TAG).i("phone wipe: wiping without the census server's confirmation")
                    return wipe(censusConfirmed = false)
                }
                Decision.LEAVE -> {
                    leave(decisions)
                    request.join()
                    return
                }
                else -> answer = next as CensusAnswer
            }
        }
    }

    /** Takes the lock, waiting for a census run that holds it; false when the dasher cancels the wait. */
    private suspend fun acquire(owner: Any, decisions: Channel<Decision>): Boolean {
        if (lock.mutex.tryLock(owner)) return true
        state.value = WipeProgress.WaitingForUpload
        Timber.tag(TAG).i("phone wipe: waiting for a census run to finish")
        return coroutineScope {
            val locking = async { lock.mutex.lock(owner) }
            val locked = select {
                locking.onAwait { true }
                decisions.onReceive { false }
            }
            // A lock taken in the same instant as the cancel is released by start()'s holdsLock check.
            if (!locked) locking.cancel()
            locked
        }
    }

    /** Closes the dialog; a new wipe may start (and waits for this one's lock while it still holds it). */
    private fun leave(decisions: Channel<Decision>) {
        inbox.compareAndSet(decisions, null)
        state.value = WipeProgress.Idle
    }

    /** Holding the lock: stores the commitment, then deletes. On success the process ends. */
    private suspend fun wipe(censusConfirmed: Boolean) {
        state.value = WipeProgress.Wiping(censusConfirmed)
        try {
            intent.commit()
        } catch (_: Exception) {
            Timber.tag(TAG).w("phone wipe: commitment not stored; the wipe goes on")
        }
        if (censusConfirmed) delay(CONFIRMED_NOTICE_MILLIS)
        clearStorage()
    }

    /** Holding the lock: deletes the census key, then asks Android to clear the app's storage. */
    private fun clearStorage(): Boolean {
        Timber.tag(TAG).i("phone wipe: clearing app storage")
        try {
            sealer.reset()
        } catch (_: Exception) {
            Timber.tag(TAG).w("phone wipe: census key delete failed; Android's clear removes it")
        }
        val started = try {
            clearer.clear()
        } catch (_: Exception) {
            false
        }
        if (!started) {
            Timber.tag(TAG).w("phone wipe: Android did not clear app storage")
            runCatching { intent.clear() }
            state.value = WipeProgress.Failed
        }
        return started
    }

    /** True or false when the credential store answers; null when it cannot be read. */
    private suspend fun readEnrolled(): Boolean? = try {
        credentials.installId.first() != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Timber.tag(TAG).w("phone wipe: census credential store unreadable")
        null
    }

    private suspend fun Deferred<CensusDeletion>.answer(): CensusAnswer {
        val outcome = try {
            await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return CensusAnswer.NotConfirmed(WipeProgress.Reason.UNREACHABLE)
        }
        return when (outcome.state) {
            CensusDeletion.State.DELETED -> CensusAnswer.Confirmed
            CensusDeletion.State.NOTHING_SENT -> CensusAnswer.NothingSent
            CensusDeletion.State.NOT_POSSIBLE -> CensusAnswer.NotConfirmed(WipeProgress.Reason.NOT_POSSIBLE)
            CensusDeletion.State.PENDING -> CensusAnswer.NotConfirmed(
                when (outcome.waiting) {
                    CensusDeletion.Waiting.NOT_YET -> WipeProgress.Reason.NOT_YET
                    CensusDeletion.Waiting.UNCONFIRMED -> WipeProgress.Reason.UNCONFIRMED
                    CensusDeletion.Waiting.UNREACHABLE -> WipeProgress.Reason.UNREACHABLE
                    null -> WipeProgress.Reason.NO_ANSWER
                },
            )
        }
    }

    companion object {
        /** How long the dialog waits for the census server's answer before offering the choice. */
        const val CENSUS_WAIT_MILLIS = 20_000L

        /** How long the census server's confirmation stays on screen before the clear. */
        const val CONFIRMED_NOTICE_MILLIS = 3_000L
        private const val TAG = "Wipe"
    }
}
