package cloud.trotter.dashbuddy.state.effects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #991 — the speech-engine recovery ladder, exercised as pure logic: no engine, no Android, no
 * real clock (every `now` is a literal).
 */
class TtsRecoveryPolicyTest {

    private val step = TtsRecoveryPolicy.BACKOFF_STEP_MILLIS
    private val cap = TtsRecoveryPolicy.MAX_BACKOFF_MILLIS
    private val floor = TtsRecoveryPolicy.MIN_STREAK_MILLIS

    /**
     * Two failures in: the rebuild gate is satisfied, the streak gate is one short. The next
     * failure at [notifyAt] clears every gate — and is also past its backoff window, so the
     * rebuild and the notice fall due together.
     */
    private fun policyAtNotifyBrink(): TtsRecoveryPolicy = TtsRecoveryPolicy().apply {
        onSpeechFailure(0L)
        onSpeechFailure(step)
    }

    /** Past the second rebuild's 60 s backoff window AND past the 60 s streak floor. */
    private val notifyAt = 3 * step

    @Test
    fun `the first failure rebuilds the engine immediately`() {
        val policy = TtsRecoveryPolicy()

        val decision = policy.onSpeechFailure(1_000L)

        assertTrue(decision.rebuild)
        assertFalse(decision.notify)
        assertEquals(1, policy.failureStreak)
    }

    @Test
    fun `a second failure inside the backoff window waits`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(0L)

        val decision = policy.onSpeechFailure(step - 1)

        assertTrue("nothing is due yet", decision.isWait)
    }

    @Test
    fun `a second failure once the window has elapsed rebuilds again`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(0L)

        assertTrue(policy.onSpeechFailure(step).rebuild)
    }

    @Test
    fun `the backoff grows linearly per attempt and stops at the cap`() {
        val policy = TtsRecoveryPolicy()
        assertEquals("no attempt yet, nothing to wait for", 0L, policy.currentBackoffMillis())

        policy.onSpeechFailure(0L)
        assertEquals(step, policy.currentBackoffMillis())

        // A completed utterance between failures keeps the attempt count at one each time.
        var now = 0L
        repeat(40) {
            now += cap
            policy.onUtteranceCompleted()
            policy.onSpeechFailure(now)
        }
        assertEquals(step, policy.currentBackoffMillis())

        // Without those resets the attempts accumulate and the window saturates at the cap.
        val saturating = TtsRecoveryPolicy()
        var t = 0L
        repeat(40) {
            saturating.onSpeechFailure(t)
            t += cap
        }
        assertEquals(cap, saturating.currentBackoffMillis())
    }

    // ---------------------------------------------------------------------------------------
    // The notify gate (review finding 4: the notice is once per process and must not be burnt
    // by a burst of offers landing during one slow-but-successful rebuild).
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a sustained outage notifies`() {
        val policy = policyAtNotifyBrink()

        val decision = policy.onSpeechFailure(notifyAt)

        assertTrue("streak, rebuilds and elapsed floor all satisfied", decision.notify)
    }

    @Test
    fun `three rapid failures inside the elapsed floor do not notify`() {
        val policy = TtsRecoveryPolicy()

        // Three offers arriving seconds apart while ONE rebuild is still coming up.
        val decisions = listOf(0L, 1_000L, 2_000L).map { policy.onSpeechFailure(it) }

        assertTrue(
            "a burst is not an outage — the once-per-process notice must survive it",
            decisions.none { it.notify },
        )
        assertEquals(3, policy.failureStreak)

        // And the notice is still available once the outage proves itself.
        assertTrue(policy.onSpeechFailure(floor + step).notify)
    }

    @Test
    fun `a streak with only one rebuild attempted does not notify`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(0L)
        // Both later failures land inside the first backoff window, so no SECOND rebuild is
        // attempted — nothing has yet proved a rebuild doesn't help.
        policy.onSpeechFailure(step - 2)
        val decision = policy.onSpeechFailure(step - 1)

        assertFalse(decision.notify)
    }

    @Test
    fun `two failures never notify however long they take`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(0L)

        assertFalse(policy.onSpeechFailure(10 * floor).notify)
    }

    @Test
    fun `notify fires at most once per process, even across a recovery`() {
        val policy = policyAtNotifyBrink()
        assertTrue(policy.onSpeechFailure(notifyAt).notify)

        // The engine comes back, then dies again: the streak restarts but the disclosure does not.
        policy.onUtteranceCompleted()
        val second = (3..10).map { policy.onSpeechFailure(it * cap) }

        assertEquals("one notice per process", 0, second.count { it.notify })
    }

    @Test
    fun `notifying and rebuilding compose`() {
        val policy = policyAtNotifyBrink()

        // The notifying failure is also past its backoff window, so the rebuild is due too —
        // telling the dasher is not a reason to skip the attempt that might fix it (finding 6).
        val decision = policy.onSpeechFailure(notifyAt)

        assertTrue(decision.notify)
        assertTrue(decision.rebuild)
    }

    @Test
    fun `recovery keeps retrying after the notice`() {
        val policy = policyAtNotifyBrink()
        policy.onSpeechFailure(notifyAt)

        assertTrue(
            "a told dasher is not an abandoned engine",
            policy.onSpeechFailure(notifyAt + cap).rebuild,
        )
    }

    @Test
    fun `a completed utterance resets the streak and the backoff`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(0L)
        policy.onSpeechFailure(step)
        policy.onUtteranceCompleted()

        assertEquals(0, policy.failureStreak)
        assertEquals(0L, policy.currentBackoffMillis())
        // Back to square one: the next failure rebuilds immediately rather than waiting out a
        // window inherited from an episode that is over.
        assertTrue(policy.onSpeechFailure(step + 1).rebuild)
    }

    @Test
    fun `a backwards clock step does not freeze recovery`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(10 * cap)

        // NTP correction lands the next failure "before" the last attempt. Waiting for the clock
        // to catch up would silence the voice for as long as the jump was.
        assertTrue(policy.onSpeechFailure(0L).rebuild)
    }

    @Test
    fun `a backwards clock step does not manufacture an early notice`() {
        val policy = TtsRecoveryPolicy()
        policy.onSpeechFailure(10 * floor)
        policy.onSpeechFailure(10 * floor + step)

        // The streak re-anchors on the earlier instant rather than measuring a fabricated
        // multi-minute elapsed against the original one.
        assertFalse(policy.onSpeechFailure(0L).notify)
    }
}
