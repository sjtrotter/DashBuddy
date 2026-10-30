package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.format.Formats

object GoalPillMapper {

    fun map(goal: Double?, todayProfit: Double?): GoalPillState {
        if (goal == null || !(goal > 0)) return GoalPillState.NoGoal
        if (todayProfit == null) return GoalPillState.InProgress(EMPTY_VALUE, 0f)
        val profit = todayProfit
        // Whole cents: 100.01 - 63.01 in doubles is 37.000000000000014, and a ceil of that is 38.
        val remainingCents = Formats.cents(goal) - Formats.cents(profit)
        if (remainingCents <= 0) return GoalPillState.Met
        val wholeDollars = (remainingCents + 99) / 100
        return GoalPillState.InProgress(Formats.money0(wholeDollars.toDouble()), (profit / goal).toFloat().coerceIn(0f, 1f))
    }
}
