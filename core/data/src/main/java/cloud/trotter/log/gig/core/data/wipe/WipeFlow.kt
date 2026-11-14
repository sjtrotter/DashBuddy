package cloud.trotter.log.gig.core.data.wipe

/**
 * #1579: Delete everything on this phone as a pure reducer (CLAUDE.md principle 1). [PhoneWipe]'s actor owns the
 * census upload lock, feeds every event through one channel in arrival order (a census answer included, before or after
 * the dialog's timeout) and executes the effects; this object only decides.
 *
 * The rules it keeps: nothing is deleted before the lock is held and the commitment is written ([State.Committing] ->
 * [Event.CommitWritten] -> [State.Wiping]); a cancel accepted before the commitment ends the flow with nothing
 * deleted; the lock is released only when no census request is outstanding ([State.Releasing] waits for it).
 */
object WipeFlow {

    enum class Enrollment { ENROLLED, NOT_ENROLLED, UNKNOWN }

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

    /** What #1622's request came to. */
    sealed interface Answer {
        data object Confirmed : Answer

        /** The request found no enrolled ID, so nothing was sent and nothing on the server is tied to this phone. */
        data object NothingSent : Answer
        data class NotConfirmed(val reason: Reason) : Answer
    }

    enum class Failure {
        /** The commitment could not be written, so nothing was deleted. */
        COMMIT,

        /** Android did not start the clear; only the census key is gone. */
        CLEAR,
    }

    sealed interface State {
        /** The one confirmation is shown; nothing is held. */
        data object Confirming : State

        /** Waiting for the census upload lock (a census run holds it). */
        data object WaitingForLock : State

        /** Holding the lock, reading whether the phone has an enrolled census ID. */
        data object ReadingEnrollment : State

        /** The census deletion request is out; the dialog waits for its answer or the timeout. */
        data object Asking : State

        /** Not confirmed: Delete now or Wait. [requestOpen]: the request is still running under the lock. */
        data class Choosing(val reason: Reason, val requestOpen: Boolean) : State

        /** Writing the commitment; nothing is deleted yet. */
        data class Committing(val censusConfirmed: Boolean, val requestOpen: Boolean) : State

        /** Committed: deleting the census key, then Android's clear. */
        data class Wiping(val censusConfirmed: Boolean, val requestOpen: Boolean) : State

        data class Failed(val failure: Failure, val requestOpen: Boolean) : State

        /** The dasher left with a request still running; the lock is released when it finishes. */
        data object Releasing : State

        /** Ended with nothing deleted and nothing held. */
        data object Left : State

        /** Android started the clear; the process ends. */
        data object Done : State
    }

    sealed interface Event {
        data object UserConfirm : Event
        data object UserCancel : Event
        data object UserDeleteNow : Event
        data object UserWait : Event
        data object LockAcquired : Event
        data class EnrollmentRead(val enrollment: Enrollment) : Event
        data class RequestResult(val answer: Answer) : Event
        data object DialogTimeout : Event
        data object CommitWritten : Event
        data object CommitFailed : Event
        data object NoticeElapsed : Event
        data object ClearStarted : Event
        data object ClearRefused : Event
    }

    enum class Effect {
        ACQUIRE_LOCK,
        CANCEL_ACQUIRE,
        READ_ENROLLMENT,
        SEND_REQUEST,
        START_TIMER,
        COMMIT,
        START_NOTICE,
        CLEAR,
        DROP_COMMIT,
        RELEASE_LOCK,
    }

    data class Step(val state: State, val effects: List<Effect> = emptyList())

    val State.terminal: Boolean get() = this == State.Left || this == State.Done

    fun step(state: State, event: Event): Step = when (state) {
        State.Confirming -> when (event) {
            Event.UserConfirm -> Step(State.WaitingForLock, listOf(Effect.ACQUIRE_LOCK))
            Event.UserCancel -> Step(State.Left)
            else -> Step(state)
        }
        State.WaitingForLock -> when (event) {
            Event.LockAcquired -> Step(State.ReadingEnrollment, listOf(Effect.READ_ENROLLMENT))
            Event.UserCancel -> Step(State.Left, listOf(Effect.CANCEL_ACQUIRE))
            else -> Step(state)
        }
        State.ReadingEnrollment -> when (event) {
            is Event.EnrollmentRead -> when (event.enrollment) {
                Enrollment.NOT_ENROLLED -> Step(State.Committing(false, requestOpen = false), listOf(Effect.COMMIT))
                Enrollment.ENROLLED, Enrollment.UNKNOWN ->
                    Step(State.Asking, listOf(Effect.SEND_REQUEST, Effect.START_TIMER))
            }
            Event.UserCancel -> Step(State.Left, listOf(Effect.RELEASE_LOCK))
            else -> Step(state)
        }
        State.Asking -> when (event) {
            is Event.RequestResult -> answered(event.answer)
            Event.DialogTimeout -> Step(State.Choosing(Reason.NO_ANSWER, requestOpen = true))
            else -> Step(state)
        }
        is State.Choosing -> when (event) {
            Event.UserDeleteNow -> Step(State.Committing(false, state.requestOpen), listOf(Effect.COMMIT))
            Event.UserWait, Event.UserCancel ->
                if (state.requestOpen) Step(State.Releasing) else Step(State.Left, listOf(Effect.RELEASE_LOCK))
            is Event.RequestResult -> if (state.requestOpen) answered(event.answer) else Step(state)
            else -> Step(state)
        }
        is State.Committing -> when (event) {
            Event.CommitWritten -> Step(
                State.Wiping(state.censusConfirmed, state.requestOpen),
                listOf(if (state.censusConfirmed) Effect.START_NOTICE else Effect.CLEAR),
            )
            Event.CommitFailed -> failed(Failure.COMMIT, state.requestOpen, drop = false)
            is Event.RequestResult -> Step(state.copy(requestOpen = false))
            else -> Step(state)
        }
        is State.Wiping -> when (event) {
            Event.NoticeElapsed -> Step(state, listOf(Effect.CLEAR))
            Event.ClearStarted -> Step(State.Done)
            Event.ClearRefused -> failed(Failure.CLEAR, state.requestOpen, drop = true)
            is Event.RequestResult -> Step(state.copy(requestOpen = false))
            else -> Step(state)
        }
        is State.Failed -> when (event) {
            is Event.RequestResult ->
                if (state.requestOpen) Step(state.copy(requestOpen = false), listOf(Effect.RELEASE_LOCK)) else Step(state)
            Event.UserCancel -> if (state.requestOpen) Step(State.Releasing) else Step(State.Left)
            else -> Step(state)
        }
        State.Releasing -> when (event) {
            is Event.RequestResult -> Step(State.Left, listOf(Effect.RELEASE_LOCK))
            else -> Step(state)
        }
        State.Left -> when (event) {
            // A lock taken in the instant the wait was cancelled is handed straight back.
            Event.LockAcquired -> Step(state, listOf(Effect.RELEASE_LOCK))
            else -> Step(state)
        }
        State.Done -> Step(state)
    }

    private fun answered(answer: Answer): Step = when (answer) {
        Answer.Confirmed -> Step(State.Committing(true, requestOpen = false), listOf(Effect.COMMIT))
        Answer.NothingSent -> Step(State.Committing(false, requestOpen = false), listOf(Effect.COMMIT))
        is Answer.NotConfirmed -> Step(State.Choosing(answer.reason, requestOpen = false))
    }

    private fun failed(failure: Failure, requestOpen: Boolean, drop: Boolean): Step = Step(
        State.Failed(failure, requestOpen),
        listOfNotNull(Effect.DROP_COMMIT.takeIf { drop }, Effect.RELEASE_LOCK.takeIf { !requestOpen }),
    )
}
