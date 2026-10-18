package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.domain.model.cards.*
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

class GoalPillMapperTest {
    @Test
    fun `no goal gives set goal`() {
        assertEquals(GoalPillState.NoGoal, GoalPillMapper.map(null, 50.0))
    }

    @Test
    fun `zero or negative goal gives set goal`() {
        assertEquals(GoalPillState.NoGoal, GoalPillMapper.map(0.0, null))
        assertEquals(GoalPillState.NoGoal, GoalPillMapper.map(-1.0, 30.0))
    }

    @Test
    fun `no profit today gives the full goal to go and zero progress`() {
        assertEquals(GoalPillState.InProgress(Formats.money0(150.0), 0f), GoalPillMapper.map(150.0, null))
    }

    @Test
    fun `remainder rounds up to whole dollars`() {
        assertEquals(GoalPillState.InProgress(Formats.money0(79.0), (71.2 / 150).toFloat()), GoalPillMapper.map(150.0, 71.2))
    }

    @Test
    fun `remainder under a dollar shows one dollar`() {
        val state = GoalPillMapper.map(150.0, 149.6) as GoalPillState.InProgress
        assertEquals(Formats.money0(1.0), state.remaining)
    }

    @Test
    fun `profit equal to the goal is met`() {
        assertEquals(GoalPillState.Met, GoalPillMapper.map(150.0, 150.0))
    }

    @Test
    fun `profit over the goal is met with no overage`() {
        assertEquals(GoalPillState.Met, GoalPillMapper.map(150.0, 170.0))
    }

    @Test
    fun `a loss today clamps progress at zero`() {
        assertEquals(GoalPillState.InProgress(Formats.money0(170.0), 0f), GoalPillMapper.map(150.0, -20.0))
    }
}
