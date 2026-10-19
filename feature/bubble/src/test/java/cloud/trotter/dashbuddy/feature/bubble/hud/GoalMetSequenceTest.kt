package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

class GoalMetSequenceTest {
    private fun frame(ms: Long) = GoalMetSequence.frameAt(ms, .8f)
    @Test fun phases_sumTo1200ms() { assertEquals(1200, GoalMetSequence.DURATION_MS) }
    @Test fun `boundaries follow the constants`() {
        val phases = listOf(GoalMetPhase.COLLAPSE, GoalMetPhase.CLOSE, GoalMetPhase.CHECK, GoalMetPhase.SETTLE)
        val durations = listOf(GoalMetSequence.COLLAPSE_MS, GoalMetSequence.CLOSE_MS,
            GoalMetSequence.CHECK_MS, GoalMetSequence.SETTLE_MS)
        var boundary = 0L
        phases.zip(durations).forEachIndexed { index, (phase, duration) ->
            assertEquals(phase, frame(boundary).phase)
            boundary += duration
            assertEquals(phase, frame(boundary - 1).phase)
            assertEquals(phases.getOrElse(index + 1) { GoalMetPhase.DONE }, frame(boundary).phase)
        }
        assertEquals(GoalMetSequence.DURATION_MS.toLong(), boundary)
    }
    @Test fun frameAtZero_isFullPillWithText() {
        assertEquals(GoalMetPhase.COLLAPSE, frame(0).phase)
        assertEquals(1f, frame(0).textAlpha, 0f)
        assertEquals(0f, frame(0).collapse, 0f)
    }
    @Test fun frameAt300_textGoneAndCollapsed() {
        assertEquals(GoalMetPhase.CLOSE, frame(300).phase)
        assertEquals(0f, frame(300).textAlpha, 0f)
        assertEquals(1f, frame(300).collapse, 0f)
    }
    @Test fun frameAt700_arcClosedInAccent() {
        assertEquals(1f, frame(700).arcSweep, 0f)
        assertEquals(1f, frame(700).arcAccent, 0f)
    }
    @Test fun frameBetween700And1099_showsCheck() {
        for (ms in 700L..1099L) assertEquals(GoalIcon.CHECK, frame(ms).icon)
    }
    @Test fun frameAt1100_showsFilledFlag() {
        assertEquals(GoalMetPhase.SETTLE, frame(1100).phase)
        assertEquals(GoalIcon.FILLED_FLAG, frame(1100).icon)
    }
    @Test fun frameAfterEnd_equalsStaticMet() {
        assertEquals(GoalMetFrame(GoalMetPhase.DONE, 0f, 1f, 1f, 1f, GoalIcon.FILLED_FLAG), frame(2000))
    }
    @Test fun arcStartsAtLastKnownProgress() {
        assertEquals(.8f, frame(0).arcSweep, 0f)
        assertEquals(.8f, frame(300).arcSweep, 0f)
        assertTrue(frame(500).arcSweep in .8f..1f)
    }
    @Test fun negativeElapsed_clampsToStart() { assertEquals(frame(0), frame(-10)) }
}
