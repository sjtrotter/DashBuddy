package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.format.Formats
import kotlin.math.ceil

object GoalPillMapper {
    fun map(goal: Double?, todayProfit: Double?): GoalPillState {
        if (goal == null || !(goal > 0)) return GoalPillState.NoGoal
        val profit = todayProfit ?: 0.0
        if (profit >= goal) return GoalPillState.Met
        return GoalPillState.InProgress(Formats.money0(ceil(goal - profit)), (profit / goal).toFloat().coerceIn(0f, 1f))
    }
}
