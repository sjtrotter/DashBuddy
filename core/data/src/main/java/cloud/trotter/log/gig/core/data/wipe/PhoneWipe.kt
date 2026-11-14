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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Where the wipe is (#1579). The settings dialog renders it; [PhoneWipe] owns it. */
sealed interface WipeProgress {
    data object Idle : WipeProgress

    /** Waiting for the census server's answer, at most [PhoneWipe.CENSUS_WAIT_MILLIS]. */
    data object AskingCensus : WipeProgress

    /** Deleting. [censusConfirmed]: the census server confirmed its deletion first, and the screen says so. */
    data class Wiping(val censusConfirmed: Boolean) : WipeProgress

    /** The census request is not confirmed: the dasher picks between wiping now and waiting. Nothing is deleted yet. */
    data class CensusNotConfirmed(val reason: Reason) : WipeProgress

    /** Android did not start the clear; nothing is deleted except the census key. */
    data object Failed : WipeProgress

    enum class Reason {
        /** No outcome in time; the request goes on in the background and its answer is stored. */
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
 * #1579: Settings > Data & privacy > Delete everything on this phone, run on the application scope so leaving the
 * screen cannot stop it half-done.
 *
 * With an enrolled census ID on the phone, [start] first sends the census deletion request (#1622) under the census
 * upload lock and waits at most [CENSUS_WAIT_MILLIS] for the answer. A confirmed deletion goes straight on to the wipe
 * without releasing the lock, so no census run can upload between the server's confirmation and the clear; an
 * unconfirmed one stops at [WipeProgress.CensusNotConfirmed] with nothing deleted, because wiping drops the census ID
 * and the pending request with it. [wipeNow] is the dasher's choice to wipe anyway; [dismiss] is "Wait". With no
 * enrolled census ID nothing is sent.
 *
 * The wipe itself is Android's own "Clear storage" ([AppStorageClearer]). Android stops every gig.log process, service
 * and scheduled job, then deletes all of the app's private storage (the database with its WAL and SHM files and its
 * pre-migration snapshots, every DataStore file, the logs, captures, census files and WorkManager's database) and its
 * Keystore keys, so nothing in the app writes in between and a store added later needs no list to stay covered. The
 * census Keystore key is deleted here too, first. Exports, gallery screenshots and Android's backup live outside the
 * app's storage and stay.
 */
@Singleton
class PhoneWipe @Inject constructor(
    private val credentials: CensusCredentialStore,
    private val deletion: CensusDataDeletion,
    private val lock: CensusUploadLock,
    private val sealer: KeystoreSealer,
    private val clearer: AppStorageClearer,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val state = MutableStateFlow<WipeProgress>(WipeProgress.Idle)
    private val starting = AtomicBoolean(false)
    val progress: StateFlow<WipeProgress> = state.asStateFlow()

    /** Whether the phone holds an enrolled census ID, so the server may hold uploads under it. Reads no secret. */
    suspend fun censusEnrolled(): Boolean = try {
        credentials.installId.first() != null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Timber.tag(TAG).w("phone wipe: census credential store unreadable")
        false
    }

    /** The confirmation's Delete everything. Ignored while a wipe is starting or [progress] is not [WipeProgress.Idle]. */
    fun start() {
        if (state.value != WipeProgress.Idle || !starting.compareAndSet(false, true)) return
        scope.launch {
            val enrolled = try {
                censusEnrolled()
            } finally {
                starting.set(false)
            }
            if (!enrolled) {
                state.value = WipeProgress.Wiping(censusConfirmed = false)
                clear(holdingLock = withLock(LOCK_WAIT_MILLIS))
                return@launch
            }
            state.value = WipeProgress.AskingCensus
            Timber.tag(TAG).i("phone wipe: asking the census server first")
            when (val answer = askCensusHoldingLock()) {
                CensusAnswer.Confirmed -> {
                    state.value = WipeProgress.Wiping(censusConfirmed = true)
                    delay(CONFIRMED_NOTICE_MILLIS)
                    clear(holdingLock = true)
                }
                CensusAnswer.NothingSent -> {
                    state.value = WipeProgress.Wiping(censusConfirmed = false)
                    clear(holdingLock = true)
                }
                is CensusAnswer.NotConfirmed -> {
                    Timber.tag(TAG).i("phone wipe: census not confirmed reason=%s",
                        answer.reason.name.lowercase(Locale.ROOT))
                    state.value = WipeProgress.CensusNotConfirmed(answer.reason)
                }
            }
        }
    }

    /** "Delete now" after an unconfirmed census request: the server keeps what it holds for this phone. */
    fun wipeNow() {
        val current = state.value as? WipeProgress.CensusNotConfirmed ?: return
        if (!state.compareAndSet(current, WipeProgress.Wiping(censusConfirmed = false))) return
        Timber.tag(TAG).i("phone wipe: wiping without the census server's confirmation")
        scope.launch { clear(holdingLock = withLock(LOCK_WAIT_MILLIS)) }
    }

    /** "Wait" or "OK": closes the dialog and changes nothing. A pending census request keeps retrying (#1622). */
    fun dismiss() {
        val current = state.value
        if (current is WipeProgress.CensusNotConfirmed || current == WipeProgress.Failed) {
            state.compareAndSet(current, WipeProgress.Idle)
        }
    }

    private sealed interface CensusAnswer {
        data object Confirmed : CensusAnswer
        /** No enrolled ID after all, so nothing was sent; nothing to keep the phone's data for. */
        data object NothingSent : CensusAnswer
        data class NotConfirmed(val reason: WipeProgress.Reason) : CensusAnswer
    }

    /**
     * Takes the upload lock and sends the deletion under it, all within [CENSUS_WAIT_MILLIS]. On [CensusAnswer.Confirmed]
     * and [CensusAnswer.NothingSent] the lock is still held for the clear; otherwise it is released once the request
     * finishes, never before, since a request that outlives the wait still runs under it.
     */
    private suspend fun askCensusHoldingLock(): CensusAnswer {
        var locked = false
        var request: Deferred<CensusDeletion>? = null
        val outcome = try {
            withTimeoutOrNull(CENSUS_WAIT_MILLIS) {
                lock.mutex.lock(this@PhoneWipe)
                locked = true
                scope.async { deletion.requestHoldingLock() }.also { request = it }.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val answer = if (outcome == null) CensusAnswer.NotConfirmed(WipeProgress.Reason.NO_ANSWER) else when (outcome.state) {
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
        if (answer is CensusAnswer.NotConfirmed && locked) {
            val running = request
            scope.launch {
                running?.join()
                lock.mutex.unlock(this@PhoneWipe)
            }
        }
        return answer
    }

    private suspend fun withLock(waitMillis: Long): Boolean =
        withTimeoutOrNull(waitMillis) { lock.mutex.lock(this@PhoneWipe) } != null

    /** Deletes the census key, then asks Android to clear the app's storage. On success the process ends. */
    private fun clear(holdingLock: Boolean) {
        Timber.tag(TAG).i("phone wipe: clearing app storage censusLock=%s", holdingLock)
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
            if (holdingLock) lock.mutex.unlock(this)
            state.value = WipeProgress.Failed
        }
    }

    companion object {
        /** How long the wipe waits for the census server's answer before offering the choice. */
        const val CENSUS_WAIT_MILLIS = 20_000L

        /** How long the census server's confirmation stays on screen before the clear. */
        const val CONFIRMED_NOTICE_MILLIS = 3_000L

        /** How long a wipe without a census answer waits for a census run to finish its batch. */
        internal const val LOCK_WAIT_MILLIS = 5_000L
        private const val TAG = "Wipe"
    }
}
