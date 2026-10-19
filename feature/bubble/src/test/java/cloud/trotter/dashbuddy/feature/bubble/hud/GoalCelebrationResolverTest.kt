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
