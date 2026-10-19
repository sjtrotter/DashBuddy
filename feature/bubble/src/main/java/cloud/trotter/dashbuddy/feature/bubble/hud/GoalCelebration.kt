package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.format.Formats
import java.time.LocalDate

object GoalMetMark {
    fun key(date: LocalDate, goal: Double): String = "$date|${Formats.cents(goal)}"
}

sealed interface MarkRead {
    data object Loading : MarkRead
    data class Loaded(val key: String?) : MarkRead
}

object GoalCelebrationResolver {
    fun resolve(goal: GoalPillState, mode: HudMode, day: LocalDate?, goalDollars: Double?, mark: MarkRead, logOpen: Boolean = false): String? {
        if (logOpen || goal != GoalPillState.Met || mode == HudMode.OFFER || mode == HudMode.LOG ||
            day == null || goalDollars == null || mark !is MarkRead.Loaded) return null
        return GoalMetMark.key(day, goalDollars).takeUnless { it == mark.key }
    }
}
