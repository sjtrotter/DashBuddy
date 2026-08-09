package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.state.effects.TtsRecoveryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #991 — the speech-engine recovery ladder, exercised as pure logic: no engine, no Android, no
 * real clock (every `now` is a literal).
 */
class TtsRecoveryPolicyTest {

    private val step = TtsRecoveryPolicy.BACKOFF_STEP_MILLIS
    private val cap = TtsRecoveryPolicy.MAX_BACKOFF_MILLIS

    @Test
    fun `the first failure rebuilds the engine immediately`() {
        val policy = TtsRecoveryPolicy()

        assertEquals(Decision.RETRY_REINIT, policy.onSpeakFailure(1_000L))
        assertEquals(1, policy.failureStreak)
    }

    @Test
    fun `a second failure inside the backoff window waits`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeakFailure(0L)

        assertEquals(Decision.WAIT, policy.onSpeakFailure(step - 1))
    }

    @Test
    fun `a second failure once the window has elapsed rebuilds again`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeakFailure(0L)

        assertEquals(Decision.RETRY_REINIT, policy.onSpeakFailure(step))
    }

    @Test
    fun `the backoff grows linearly per attempt and stops at the cap`() {
        val policy = TtsRecoveryPolicy()
        assertEquals("no attempt yet, nothing to wait for", 0L, policy.currentBackoffMillis())

        policy.onSpeakFailure(0L)
        assertEquals(step, policy.currentBackoffMillis())

        // Walk far past the cap: each accepted rebuild widens the window by one step until the
        // ceiling, then stays there.
        var now = 0L
        repeat(40) {
            now += cap
            policy.onSpeakSuccess() // keep the streak clear so NOTIFY never pre-empts the walk
            policy.onSpeakFailure(now)
        }
        assertEquals(step, policy.currentBackoffMillis())

        // Without the success resets, attempts accumulate and the window saturates at the cap.
        val saturating = TtsRecoveryPolicy()
        var t = 0L
        repeat(40) {
            saturating.onSpeakFailure(t)
            t += cap
        }
        assertEquals(cap, saturating.currentBackoffMillis())
    }

    @Test
    fun `the third consecutive failure notifies`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeakFailure(0L)
        policy.onSpeakFailure(step)

        assertEquals(Decision.NOTIFY, policy.onSpeakFailure(2 * step))
        assertEquals(TtsRecoveryPolicy.NOTIFY_AFTER_FAILURES, policy.failureStreak)
    }

    @Test
    fun `notify fires at most once per process, even across a recovery`() {
        val policy = TtsRecoveryPolicy()
        repeat(3) { i -> policy.onSpeakFailure(i * cap) }

        // The engine comes back, then dies again: the streak restarts but the disclosure does not.
        policy.onSpeakSuccess()
        val second = (3..8).map { policy.onSpeakFailure(it * cap) }

        assertEquals("one notice per process", 0, second.count { it == Decision.NOTIFY })
    }

    @Test
    fun `recovery keeps retrying after the notice`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeakFailure(0L)
        policy.onSpeakFailure(cap)
        assertEquals(Decision.NOTIFY, policy.onSpeakFailure(2 * cap))

        assertEquals(
            "a told dasher is not an abandoned engine",
            Decision.RETRY_REINIT,
            policy.onSpeakFailure(3 * cap),
        )
    }

    @Test
    fun `a success resets the streak and the backoff`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeakFailure(0L)
        policy.onSpeakFailure(step)
        policy.onSpeakSuccess()

        assertEquals(0, policy.failureStreak)
        assertEquals(0L, policy.currentBackoffMillis())
        // Back to square one: the next failure rebuilds immediately rather than waiting out a
        // window inherited from an episode that is over.
        assertEquals(Decision.RETRY_REINIT, policy.onSpeakFailure(step + 1))
    }

    @Test
    fun `a backwards clock step does not freeze recovery`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeakFailure(10 * cap)

        // NTP correction lands the next failure "before" the last attempt. Waiting for the clock
        // to catch up would silence the voice for as long as the jump was.
        assertEquals(Decision.RETRY_REINIT, policy.onSpeakFailure(0L))
    }
}
