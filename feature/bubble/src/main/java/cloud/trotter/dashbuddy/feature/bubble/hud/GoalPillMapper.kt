package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign

object GoalPillMapper {
    /** Half away from zero, the money formatter's rounding; `roundToLong` alone takes -0.5 to 0. */
    private fun cents(amount: Double): Long = (abs(amount) * 100).roundToLong() * sign(amount).toLong()

    fun map(goal: Double?, todayProfit: Double?): GoalPillState {
        if (goal == null || !(goal > 0)) return GoalPillState.NoGoal
        if (todayProfit == null) return GoalPillState.InProgress(EMPTY_VALUE, 0f)
        val profit = todayProfit
        // Whole cents: 100.01 - 63.01 in doubles is 37.000000000000014, and a ceil of that is 38.
        val remainingCents = cents(goal) - cents(profit)
        if (remainingCents <= 0) return GoalPillState.Met
        val wholeDollars = (remainingCents + 99) / 100
        return GoalPillState.InProgress(Formats.money0(wholeDollars.toDouble()), (profit / goal).toFloat().coerceIn(0f, 1f))
    }
}
