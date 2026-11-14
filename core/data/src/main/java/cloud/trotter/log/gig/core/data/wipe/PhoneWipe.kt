package cloud.trotter.log.gig.core.data.wipe

import cloud.trotter.log.gig.core.data.census.CensusCredentialStore
import cloud.trotter.log.gig.core.data.census.CensusDataDeletion
import cloud.trotter.log.gig.core.data.census.CensusUploadLock
import cloud.trotter.log.gig.core.data.census.KeystoreSealer
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Effect
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Event
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.State
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.terminal
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1579: Settings > Data & privacy > Delete everything on this phone. [WipeFlow] decides; this is its actor. One actor
 * per opened confirmation runs on the application scope, owns the census upload lock from [Event.LockAcquired] until
 * the flow ends, and turns every outcome (the lock, the enrollment read, the census answer, the dialog's timeout, the
 * commitment, Android's clear) and every choice of the dasher into an [Event] on one channel, handled in order. So a
 * census answer is never lost between checks, a cancel accepted before the commitment is final, and the lock is never
 * released while the census request runs.
 *
 * Enrolled or unknown (an unreadable credential store), the actor runs #1622's [CensusDataDeletion.requestHoldingLock],
 * which stores the request as pending and queues its retry before any network attempt; [CENSUS_WAIT_MILLIS] bounds
 * only the dialog's wait. A decided wipe writes [WipeIntent] before anything is deleted, and app start finishes a
 * committed wipe before anything else runs ([resumeCommitted]).
 *
 * The wipe deletes the census Keystore key, then runs Android's own "Clear storage" ([AppStorageClearer]): Android stops
 * every gig.log process, service and scheduled job, then deletes all of the app's private storage (the database with
 * its WAL and SHM files and pre-migration snapshots, every DataStore file, logs, captures, census files, WorkManager's
 * database and the commitment) and its Keystore keys. Exports, gallery screenshots and Android's backup stay.
 */
@Singleton
class PhoneWipe @Inject constructor(
    private val credentials: CensusCredentialStore,
    private val deletion: CensusDataDeletion,
    private val preferences: DevSettingsRepository,
    private val lock: CensusUploadLock,
    private val sealer: KeystoreSealer,
    private val clearer: AppStorageClearer,
    private val intent: WipeIntent,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<State?>(null)

    /** The current flow's state for the dialog; null when none is shown (also while a left flow still releases). */
    val progress: StateFlow<State?> = state.asStateFlow()

    /**
     * Whether the confirmation names the census request and what the server keeps: true whenever a request could be
     * sent, so with the census on (an upload may enrol the phone before the wipe takes the lock), any census identity
     * stored, or a store that can't be read. Live, never a snapshot.
     */
    val censusDisclosed: Flow<Boolean> =
        combine(preferences.censusUploadEnabled, credentials.held) { on, held -> on || held }
            .catch { emit(true) }

    @Volatile private var current: Actor? = null

    /** The settings row: shows the confirmation. Ignored while a flow is shown. */
    fun open() {
        if (state.value != null) return
        val actor = Actor()
        current = actor
        state.value = State.Confirming
        scope.launch { actor.run() }
    }

    fun confirm() {
        send(Event.UserConfirm)
    }

    /** Cancel, or OK on a failure; OK on a refused resume at start lets the app start ([resumeCommitted]). */
    fun cancel() {
        if (send(Event.UserCancel) || acknowledgeRefusedResume()) return
        if (state.value is State.Failed) state.value = null
    }

    fun deleteNow() {
        send(Event.UserDeleteNow)
    }

    fun waitForServer() {
        send(Event.UserWait)
    }

    private fun send(event: Event): Boolean = current?.events?.trySend(event)?.isSuccess == true

    /** A committed wipe that Android refused at start: its lock owner and the startup to run once the dasher has seen it. */
    private class RefusedResume(val owner: Any, val onAcknowledged: () -> Unit)

    @Volatile private var refusedResume: RefusedResume? = null

    /**
     * App start, before sensing, workers or uploads: a wipe committed before the process died is finished now. Takes
     * the upload lock synchronously when it is free (at start nothing else holds it), so no census run goes first.
     * True when the caller must start nothing now: the wipe is under way (the process ends), or Android refused the
     * clear. A refusal is shown as [WipeFlow.Failure.CLEAR] ("Couldn't delete") on the first screen; the lock and the
     * commitment are kept, so no census upload runs and the next start tries again, until the dasher presses OK, which
     * drops the commitment, releases the lock and runs [onAcknowledged] (the app's normal startup). False when none was
     * committed.
     */
    fun resumeCommitted(onAcknowledged: () -> Unit): Boolean {
        val committed = try {
            intent.isCommitted()
        } catch (_: Exception) {
            false
        }
        if (!committed) return false
        Timber.tag(TAG).i("phone wipe: finishing a committed wipe at start")
        state.value = State.Wiping(censusConfirmed = false, requestOpen = false)
        val owner = Any()
        if (lock.mutex.tryLock(owner)) {
            finishResume(owner, onAcknowledged)
        } else {
            scope.launch {
                lock.mutex.lock(owner)
                finishResume(owner, onAcknowledged)
            }
        }
        return true
    }

    private fun finishResume(owner: Any, onAcknowledged: () -> Unit) {
        if (clearStorage()) return
        refusedResume = RefusedResume(owner, onAcknowledged)
        state.value = State.Failed(WipeFlow.Failure.CLEAR, requestOpen = false)
    }

    /** OK on a refused resume: the dasher has seen it; the app starts as usual with its data. */
    private fun acknowledgeRefusedResume(): Boolean {
        val refusal = refusedResume ?: return false
        refusedResume = null
        dropCommitment()
        state.value = null
        if (lock.mutex.holdsLock(refusal.owner)) lock.mutex.unlock(refusal.owner)
        Timber.tag(TAG).i("phone wipe: refused clear acknowledged; starting as usual")
        refusal.onAcknowledged()
        return true
    }

    private inner class Actor {
        val events = Channel<Event>(Channel.UNLIMITED)
        private val owner = Any()
        private var flow: State = State.Confirming
        private var acquiring: Job? = null
        private val jobs = mutableListOf<Job>()

        suspend fun run() {
            try {
                while (!flow.terminal) {
                    // Publish only once the events already queued are handled, so a step resolved at once never shows.
                    val event = events.tryReceive().getOrNull() ?: run {
                        publish()
                        events.receive()
                    }
                    val step = WipeFlow.step(flow, event)
                    if (step.state != flow) Timber.tag(TAG).i("phone wipe: %s", step.state.logName())
                    flow = step.state
                    step.effects.forEach { execute(it) }
                }
            } finally {
                acquiring?.cancelAndJoin()
                jobs.forEach { it.cancel() }
                if (flow != State.Done && lock.mutex.holdsLock(owner)) lock.mutex.unlock(owner)
                events.close()
                publish()
            }
        }

        private fun publish() {
            if (current !== this) return
            state.value = when (flow) {
                State.Left, State.Releasing -> null
                else -> flow
            }
        }

        private fun execute(effect: Effect) {
            when (effect) {
                Effect.ACQUIRE_LOCK ->
                    if (lock.mutex.tryLock(owner)) {
                        events.trySend(Event.LockAcquired)
                    } else {
                        acquiring = scope.launch {
                            lock.mutex.lock(owner)
                            events.trySend(Event.LockAcquired)
                        }
                    }
                Effect.CANCEL_ACQUIRE -> acquiring?.cancel()
                Effect.READ_ENROLLMENT -> jobs += scope.launch { events.trySend(Event.EnrollmentRead(readEnrollment())) }
                // Not tracked for cancellation: the request always finishes and always reports, under the lock.
                Effect.SEND_REQUEST -> scope.launch { events.trySend(Event.RequestResult(request())) }
                Effect.START_TIMER -> jobs += scope.launch {
                    delay(CENSUS_WAIT_MILLIS)
                    events.trySend(Event.DialogTimeout)
                }
                Effect.COMMIT -> events.trySend(
                    try {
                        intent.commit()
                        Event.CommitWritten
                    } catch (_: Exception) {
                        Timber.tag(TAG).w("phone wipe: the commitment could not be written; nothing deleted")
                        Event.CommitFailed
                    },
                )
                Effect.START_NOTICE -> jobs += scope.launch {
                    delay(CONFIRMED_NOTICE_MILLIS)
                    events.trySend(Event.NoticeElapsed)
                }
                Effect.CLEAR -> events.trySend(if (clearStorage()) Event.ClearStarted else Event.ClearRefused)
                Effect.DROP_COMMIT -> dropCommitment()
                Effect.RELEASE_LOCK -> if (lock.mutex.holdsLock(owner)) lock.mutex.unlock(owner)
            }
        }
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
        if (!started) Timber.tag(TAG).w("phone wipe: Android did not clear app storage")
        return started
    }

    private fun dropCommitment() {
        try {
            intent.clear()
        } catch (_: Exception) {
            Timber.tag(TAG).w("phone wipe: the commitment could not be removed")
        }
    }

    private suspend fun readEnrollment(): WipeFlow.Enrollment = try {
        if (credentials.installId.first() != null) WipeFlow.Enrollment.ENROLLED else WipeFlow.Enrollment.NOT_ENROLLED
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Timber.tag(TAG).w("phone wipe: census credential store unreadable")
        WipeFlow.Enrollment.UNKNOWN
    }

    private suspend fun request(): WipeFlow.Answer {
        val outcome = try {
            deletion.requestHoldingLock()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return WipeFlow.Answer.NotConfirmed(WipeFlow.Reason.UNREACHABLE)
        }
        return when (outcome.state) {
            CensusDeletion.State.DELETED -> WipeFlow.Answer.Confirmed
            CensusDeletion.State.NOTHING_SENT -> WipeFlow.Answer.NothingSent
            CensusDeletion.State.NOT_POSSIBLE -> WipeFlow.Answer.NotConfirmed(WipeFlow.Reason.NOT_POSSIBLE)
            CensusDeletion.State.PENDING -> WipeFlow.Answer.NotConfirmed(
                when (outcome.waiting) {
                    CensusDeletion.Waiting.NOT_YET -> WipeFlow.Reason.NOT_YET
                    CensusDeletion.Waiting.UNCONFIRMED -> WipeFlow.Reason.UNCONFIRMED
                    CensusDeletion.Waiting.UNREACHABLE -> WipeFlow.Reason.UNREACHABLE
                    null -> WipeFlow.Reason.NO_ANSWER
                },
            )
        }
    }

    private fun State.logName(): String = when (this) {
        is State.Choosing -> "choosing reason=${reason.name.lowercase(Locale.ROOT)} requestOpen=$requestOpen"
        is State.Failed -> "failed ${failure.name.lowercase(Locale.ROOT)} requestOpen=$requestOpen"
        else -> javaClass.simpleName.lowercase(Locale.ROOT)
    }

    companion object {
        /** How long the dialog waits for the census server's answer before offering the choice. */
        const val CENSUS_WAIT_MILLIS = 20_000L

        /** How long the census server's confirmation stays on screen before the clear. */
        const val CONFIRMED_NOTICE_MILLIS = 3_000L
        private const val TAG = "Wipe"
    }
}
