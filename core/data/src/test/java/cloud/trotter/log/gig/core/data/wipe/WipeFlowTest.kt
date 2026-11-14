package cloud.trotter.log.gig.core.data.wipe

import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Answer
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Effect
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Enrollment
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Event
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Failure
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Reason
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.State
import cloud.trotter.log.gig.core.data.wipe.WipeFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1579: [WipeFlow.step] over every state and every event: the table, then the rules it must keep everywhere. */
class WipeFlowTest {
    private val states: List<State> = buildList {
        add(State.Confirming)
        add(State.WaitingForLock)
        add(State.ReadingEnrollment)
        add(State.Asking)
        for (open in listOf(true, false)) {
            Reason.entries.forEach { add(State.Choosing(it, open)) }
            for (confirmed in listOf(true, false)) {
                add(State.Committing(confirmed, open))
                add(State.Wiping(confirmed, open))
            }
            Failure.entries.forEach { failure -> listOf(true, false).forEach { add(State.Failed(failure, open, it)) } }
        }
        add(State.Releasing)
        add(State.Left)
        add(State.Done)
    }

    private val answers: List<Answer> = listOf(Answer.Confirmed, Answer.NothingSent) +
        Reason.entries.map { Answer.NotConfirmed(it) }

    private val events: List<Event> = listOf(
        Event.UserConfirm, Event.UserCancel, Event.UserDeleteNow, Event.UserWait, Event.LockAcquired,
        Event.DialogTimeout, Event.CommitWritten, Event.CommitFailed, Event.NoticeElapsed, Event.ClearStarted,
        Event.ClearRefused, Event.CommitDropped, Event.CommitDropFailed,
    ) + Enrollment.entries.map { Event.EnrollmentRead(it) } + answers.map { Event.RequestResult(it) }

    private fun State.requestOpen(): Boolean = when (this) {
        State.Asking, State.Releasing -> true
        is State.Choosing -> requestOpen
        is State.Committing -> requestOpen
        is State.Wiping -> requestOpen
        is State.Failed -> requestOpen
        else -> false
    }

    private fun State.holdsLock(): Boolean = when (this) {
        State.ReadingEnrollment, State.Asking, State.Releasing, is State.Choosing, is State.Committing,
        is State.Wiping -> true
        is State.Failed -> requestOpen
        else -> false
    }

    private fun every(check: (State, Event, Step) -> Unit) {
        for (state in states) for (event in events) check(state, event, WipeFlow.step(state, event))
    }

    @Test fun `nothing is deleted except from Wiping, and Wiping is reached only from a written commitment`() = every { s, e, step ->
        if (Effect.CLEAR in step.effects) assertTrue("$s + $e", step.state is State.Wiping)
        if (step.state is State.Wiping && s !is State.Wiping) {
            assertTrue("$s + $e", s is State.Committing && e == Event.CommitWritten)
        }
        if (Effect.COMMIT in step.effects) assertTrue("$s + $e", step.state is State.Committing)
    }

    @Test fun `the lock is never released while a request runs`() = every { s, e, step ->
        if (Effect.RELEASE_LOCK in step.effects) {
            assertFalse("$s + $e", step.state.requestOpen())
            assertTrue("$s + $e released a lock it did not hold", s.holdsLock() || s == State.Left)
        }
        // Leaving with the lock held and no request is always paired with releasing it.
        if (step.state == State.Left && s.holdsLock()) assertTrue("$s + $e", Effect.RELEASE_LOCK in step.effects)
    }

    @Test fun `a running request is never forgotten`() = every { s, e, step ->
        if (s.requestOpen() && e !is Event.RequestResult && step.state != State.Done) {
            assertTrue("$s + $e dropped its open request", step.state.requestOpen())
        }
    }

    @Test fun `a cancel accepted before the commitment is final`() {
        for (s in listOf(State.Confirming, State.WaitingForLock, State.ReadingEnrollment)) {
            val left = WipeFlow.step(s, Event.UserCancel).state
            assertEquals(State.Left, left)
            for (e in events) assertEquals("$e after a cancel", State.Left, WipeFlow.step(left, e).state)
        }
    }

    @Test fun `a failure leaves only once no commitment is left for the next start`() = every { s, e, step ->
        if (s is State.Failed && s.commitmentStuck && step.state !is State.Failed) {
            assertEquals("$s + $e", Event.CommitDropped, e)
        }
    }

    @Test fun `a stuck commitment is retried, and leaving follows its removal`() {
        val stuck = State.Failed(Failure.CLEAR, requestOpen = false, commitmentStuck = true)
        assertEquals(Step(stuck), WipeFlow.step(State.Failed(Failure.CLEAR, false), Event.CommitDropFailed))
        assertEquals(Step(stuck, listOf(Effect.DROP_COMMIT)), WipeFlow.step(stuck, Event.UserCancel))
        assertEquals(Step(stuck), WipeFlow.step(stuck, Event.CommitDropFailed))
        assertEquals(Step(State.Left), WipeFlow.step(stuck, Event.CommitDropped))
        assertEquals(Step(State.Releasing), WipeFlow.step(stuck.copy(requestOpen = true), Event.CommitDropped))
        assertEquals("the first drop after a refused clear does not close the dialog",
            Step(State.Failed(Failure.CLEAR, false)), WipeFlow.step(State.Failed(Failure.CLEAR, false), Event.CommitDropped))
    }

    @Test fun `terminal states stay put`() = every { s, e, step ->
        if (s == State.Done) assertEquals(Step(State.Done), step)
        if (s == State.Left) {
            assertEquals(State.Left, step.state)
            assertEquals(if (e == Event.LockAcquired) listOf(Effect.RELEASE_LOCK) else emptyList<Effect>(), step.effects)
        }
    }

    @Test fun `the main path`() {
        assertEquals(Step(State.WaitingForLock, listOf(Effect.ACQUIRE_LOCK)), WipeFlow.step(State.Confirming, Event.UserConfirm))
        assertEquals(Step(State.ReadingEnrollment, listOf(Effect.READ_ENROLLMENT)),
            WipeFlow.step(State.WaitingForLock, Event.LockAcquired))
        assertEquals(Step(State.Left, listOf(Effect.CANCEL_ACQUIRE)), WipeFlow.step(State.WaitingForLock, Event.UserCancel))
        assertEquals(Step(State.Committing(false, false), listOf(Effect.COMMIT)),
            WipeFlow.step(State.ReadingEnrollment, Event.EnrollmentRead(Enrollment.NOT_ENROLLED)))
        for (enrollment in listOf(Enrollment.ENROLLED, Enrollment.UNKNOWN)) {
            assertEquals("unknown is never not enrolled", Step(State.Asking, listOf(Effect.SEND_REQUEST, Effect.START_TIMER)),
                WipeFlow.step(State.ReadingEnrollment, Event.EnrollmentRead(enrollment)))
        }
        assertEquals(Step(State.Left, listOf(Effect.RELEASE_LOCK)), WipeFlow.step(State.ReadingEnrollment, Event.UserCancel))
        assertEquals(Step(State.Committing(true, false), listOf(Effect.COMMIT)),
            WipeFlow.step(State.Asking, Event.RequestResult(Answer.Confirmed)))
        assertEquals(Step(State.Committing(false, false), listOf(Effect.COMMIT)),
            WipeFlow.step(State.Asking, Event.RequestResult(Answer.NothingSent)))
        assertEquals(Step(State.Choosing(Reason.NOT_YET, false)),
            WipeFlow.step(State.Asking, Event.RequestResult(Answer.NotConfirmed(Reason.NOT_YET))))
        assertEquals(Step(State.Choosing(Reason.NO_ANSWER, true)), WipeFlow.step(State.Asking, Event.DialogTimeout))
        assertEquals(Step(State.Asking), WipeFlow.step(State.Asking, Event.UserCancel))
    }

    @Test fun `the choice`() {
        val open = State.Choosing(Reason.NO_ANSWER, requestOpen = true)
        val closed = State.Choosing(Reason.NOT_YET, requestOpen = false)
        assertEquals(Step(State.Committing(false, true), listOf(Effect.COMMIT)), WipeFlow.step(open, Event.UserDeleteNow))
        assertEquals(Step(State.Committing(false, false), listOf(Effect.COMMIT)), WipeFlow.step(closed, Event.UserDeleteNow))
        for (leave in listOf(Event.UserWait, Event.UserCancel)) {
            assertEquals(Step(State.Releasing), WipeFlow.step(open, leave))
            assertEquals(Step(State.Left, listOf(Effect.RELEASE_LOCK)), WipeFlow.step(closed, leave))
        }
        assertEquals("a late confirmation goes on to the wipe", Step(State.Committing(true, false), listOf(Effect.COMMIT)),
            WipeFlow.step(open, Event.RequestResult(Answer.Confirmed)))
        assertEquals(Step(State.Choosing(Reason.UNREACHABLE, false)),
            WipeFlow.step(open, Event.RequestResult(Answer.NotConfirmed(Reason.UNREACHABLE))))
        assertEquals(Step(closed), WipeFlow.step(closed, Event.DialogTimeout))
        assertEquals(Step(State.Left, listOf(Effect.RELEASE_LOCK)), WipeFlow.step(State.Releasing, Event.RequestResult(Answer.Confirmed)))
    }

    @Test fun `commit, notice, clear and their failures`() {
        assertEquals(Step(State.Wiping(true, false), listOf(Effect.START_NOTICE)),
            WipeFlow.step(State.Committing(true, false), Event.CommitWritten))
        assertEquals(Step(State.Wiping(false, true), listOf(Effect.CLEAR)),
            WipeFlow.step(State.Committing(false, true), Event.CommitWritten))
        assertEquals(Step(State.Failed(Failure.COMMIT, false), listOf(Effect.RELEASE_LOCK)),
            WipeFlow.step(State.Committing(false, false), Event.CommitFailed))
        assertEquals(Step(State.Failed(Failure.COMMIT, true)), WipeFlow.step(State.Committing(false, true), Event.CommitFailed))
        assertEquals(Step(State.Wiping(true, false), listOf(Effect.CLEAR)), WipeFlow.step(State.Wiping(true, false), Event.NoticeElapsed))
        assertEquals(Step(State.Done), WipeFlow.step(State.Wiping(false, false), Event.ClearStarted))
        assertEquals(Step(State.Failed(Failure.CLEAR, false), listOf(Effect.DROP_COMMIT, Effect.RELEASE_LOCK)),
            WipeFlow.step(State.Wiping(false, false), Event.ClearRefused))
        assertEquals(Step(State.Failed(Failure.CLEAR, true), listOf(Effect.DROP_COMMIT)),
            WipeFlow.step(State.Wiping(false, true), Event.ClearRefused))
        assertEquals(Step(State.Failed(Failure.CLEAR, false), listOf(Effect.RELEASE_LOCK)),
            WipeFlow.step(State.Failed(Failure.CLEAR, true), Event.RequestResult(Answer.Confirmed)))
        assertEquals(Step(State.Releasing), WipeFlow.step(State.Failed(Failure.CLEAR, true), Event.UserCancel))
        assertEquals(Step(State.Left), WipeFlow.step(State.Failed(Failure.CLEAR, false), Event.UserCancel))
        assertEquals(Step(State.Wiping(false, false)),
            WipeFlow.step(State.Wiping(false, true), Event.RequestResult(Answer.Confirmed)))
    }
}
