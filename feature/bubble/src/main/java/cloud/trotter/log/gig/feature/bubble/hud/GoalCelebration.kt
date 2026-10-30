package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.format.Formats
import java.time.LocalDate

object GoalMetMark {
    fun key(date: LocalDate, goal: Double): String = "$date|${Formats.cents(goal)}"
}

sealed interface MarkRead {
    data object Loading : MarkRead
    data class Loaded(val key: String?) : MarkRead
}

object GoalCelebrationResolver {
    fun resolve(goal: GoalPillState, mode: HudMode, day: LocalDate?, goalDollars: Double?, mark: MarkRead,
        logOpen: Boolean = false, goalSheetOpen: Boolean = false, editedGoalKey: String? = null): String? {
        if (goalSheetOpen || logOpen || mode == HudMode.OFFER || mode == HudMode.LOG) return null
        return unmarked(goal, day, goalDollars, mark)?.takeUnless { it == editedGoalKey }
    }

    /** Editing can reach Met, but only profit crossings outside the sheet run the sequence. */
    fun markAfterEditing(goal: GoalPillState, day: LocalDate?, goalDollars: Double?, mark: MarkRead,
        goalSheetOpen: Boolean, editedGoalKey: String?): String? {
        if (goalSheetOpen) return null
        return unmarked(goal, day, goalDollars, mark)?.takeIf { it == editedGoalKey }
    }

    private fun unmarked(goal: GoalPillState, day: LocalDate?, goalDollars: Double?, mark: MarkRead): String? {
        if (goal != GoalPillState.Met || day == null || goalDollars == null || mark !is MarkRead.Loaded) return null
        return GoalMetMark.key(day, goalDollars).takeUnless { it == mark.key }
    }
}
