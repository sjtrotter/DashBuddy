package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.SessionNet
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.domain.model.cards.*
import cloud.trotter.log.gig.domain.model.event.AppEventType

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
    fun `no profit figure yet shows the em dash and zero progress`() {
        assertEquals(GoalPillState.InProgress(cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE, 0f), GoalPillMapper.map(150.0, null))
    }

    @Test
    fun `zero profit today gives the full goal to go and zero progress`() {
        assertEquals(GoalPillState.InProgress(Formats.money0(150.0), 0f), GoalPillMapper.map(150.0, 0.0))
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

    @Test
    fun `cents in the goal and the profit do not overstate the remainder`() {
        // 100.01 - 63.01 is 37.000000000000014 in doubles; a plain ceil would say 38.
        assertEquals(Formats.money0(37.0), (GoalPillMapper.map(100.01, 63.01) as GoalPillState.InProgress).remaining)
    }

    @Test
    fun `profit within a cent of the goal is met`() {
        assertEquals(GoalPillState.Met, GoalPillMapper.map(100.0, 99.999))
    }

    @Test
    fun `a negative half cent rounds away from zero like the money formatter`() {
        assertEquals(Formats.money0(2.0), (GoalPillMapper.map(1.0, -0.005) as GoalPillState.InProgress).remaining)
    }

    @Test
    fun `a loss just under a cent and a half rounds as money renders it`() {
        // money(-1.005) renders -$1.01, so $1 goal less that loss is $2.01, which is $3 to go.
        assertEquals(Formats.money0(3.0), (GoalPillMapper.map(1.0, -1.005) as GoalPillState.InProgress).remaining)
    }
}
