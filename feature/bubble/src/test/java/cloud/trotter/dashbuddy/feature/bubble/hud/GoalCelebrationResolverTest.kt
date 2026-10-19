package cloud.trotter.dashbuddy.feature.bubble.hud

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class GoalCelebrationResolverTest {
    private val day = LocalDate.of(2026, 10, 18)
    private fun resolve(mark: MarkRead = MarkRead.Loaded(null), mode: HudMode = HudMode.LIVE,
        goal: GoalPillState = GoalPillState.Met, date: LocalDate? = day, dollars: Double = 79.0, logOpen: Boolean = false) =
        GoalCelebrationResolver.resolve(goal, mode, date, dollars, mark, logOpen)
    @Test fun met_markUnset_returnsKey() { assertEquals("2026-10-18|7900", resolve()) }
    @Test fun met_markEqualsKey_returnsNull() { assertNull(resolve(MarkRead.Loaded("2026-10-18|7900"))) }
    @Test fun met_markFromYesterday_returnsKey() { assertNotNull(resolve(MarkRead.Loaded("2026-10-17|7900"))) }
    @Test fun met_goalChanged_returnsKey() { assertNotNull(resolve(MarkRead.Loaded("2026-10-18|8000"))) }
    @Test fun `stepping below profit never celebrates or marks until closing silently`() {
        var editedKey: String? = null
        var mark: MarkRead = MarkRead.Loaded(null)
        for (dollars in listOf(100.0, 95.0, 90.0)) {
            val goal = GoalPillMapper.map(dollars, 96.0)
            editedKey = if (goal == GoalPillState.Met) GoalMetMark.key(day, dollars) else null
            assertNull(GoalCelebrationResolver.resolve(goal, HudMode.LIVE, day, dollars, mark,
                goalSheetOpen = true, editedGoalKey = editedKey))
            assertNull(GoalCelebrationResolver.markAfterEditing(goal, day, dollars, mark, true, editedKey))
            if (dollars < 100.0) assertEquals(GoalPillState.Met, goal)
        }
        val goal = GoalPillMapper.map(90.0, 96.0)
        // The outstanding silent write cannot trigger a sequence on the closing frame.
        repeat(2) {
            assertNull(GoalCelebrationResolver.resolve(goal, HudMode.LIVE, day, 90.0, mark,
                goalSheetOpen = false, editedGoalKey = editedKey))
        }
        val key = GoalCelebrationResolver.markAfterEditing(goal, day, 90.0, mark, false, editedKey)
        assertEquals("2026-10-18|9000", key)
        mark = MarkRead.Loaded(key)
        assertNull(GoalCelebrationResolver.markAfterEditing(goal, day, 90.0, mark, false, editedKey))
        assertNull(GoalCelebrationResolver.resolve(goal, HudMode.LIVE, day, 90.0, mark,
            editedGoalKey = editedKey))
    }

    @Test fun `a later profit crossing with the sheet closed celebrates once`() {
        var mark: MarkRead = MarkRead.Loaded("2026-10-18|9000")
        val editedKey = "2026-10-18|9000"
        assertNull(GoalCelebrationResolver.resolve(GoalPillMapper.map(100.0, 96.0), HudMode.LIVE,
            day, 100.0, mark, editedGoalKey = editedKey))
        val key = GoalCelebrationResolver.resolve(GoalPillMapper.map(100.0, 101.0), HudMode.LIVE,
            day, 100.0, mark, editedGoalKey = editedKey)
        assertEquals("2026-10-18|10000", key)
        mark = MarkRead.Loaded(key)
        assertNull(GoalCelebrationResolver.resolve(GoalPillMapper.map(100.0, 102.0), HudMode.LIVE,
            day, 100.0, mark, editedGoalKey = editedKey))
    }

    @Test fun `closing during mark loading defers the silent write`() {
        val key = GoalMetMark.key(day, 90.0)
        assertNull(GoalCelebrationResolver.markAfterEditing(GoalPillState.Met, day, 90.0,
            MarkRead.Loading, false, key))
        assertEquals(key, GoalCelebrationResolver.markAfterEditing(GoalPillState.Met, day, 90.0,
            MarkRead.Loaded(null), false, key))
    }

    @Test fun markLoading_returnsNull() { assertNull(resolve(MarkRead.Loading)) }
    @Test fun offerMode_returnsNull() { assertNull(resolve(mode = HudMode.OFFER)) }
    @Test fun notMet_returnsNull() {
        assertNull(resolve(goal = GoalPillState.NoGoal))
        assertNull(resolve(goal = GoalPillState.InProgress("$1", .9f)))
    }
    @Test fun noDay_returnsNull() { assertNull(resolve(date = null)) }
    @Test fun key_isDateAndGoalCents() { assertEquals("2026-10-18|7912", GoalMetMark.key(day, 79.12)) }
    @Test fun key_sameForGoalsRoundingToSameCent() {
        assertEquals(GoalMetMark.key(day, 79.121), GoalMetMark.key(day, 79.124))
    }
    @Test fun logOpen_returnsNull() { assertNull(resolve(logOpen = true)) }
    @Test fun logMode_returnsNull() { assertNull(resolve(mode = HudMode.LOG)) }
}
