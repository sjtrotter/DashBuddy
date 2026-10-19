package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.feature.bubble.hud.goal.GoalBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The preference remains the source of truth; no optimistic goal or ledger event. */
internal class GoalActions(scope: CoroutineScope, private val preferences: AppPreferencesRepository) {
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    private val writer = EntryWriter<Long>(scope, "Goal") { failure.value = true }
    val busy = writer.busy
    val committed = writer.committed

    fun reopen() { failure.value = false }
    fun committedHandled() = writer.committedHandled()

    fun set(dollars: Int, closeEntryId: Long? = null) {
        if (dollars !in GoalBounds.MIN..GoalBounds.MAX) return
        write(dollars.toDouble(), closeEntryId)
    }

    fun clear(closeEntryId: Long? = null) = write(null, closeEntryId)

    private fun write(dollars: Double?, closeEntryId: Long?) {
        writer.write {
            failure.value = false
            preferences.setDailyKeptGoalDollars(dollars)
            closeEntryId?.let { writer.commit(it) }
        }
    }
}
