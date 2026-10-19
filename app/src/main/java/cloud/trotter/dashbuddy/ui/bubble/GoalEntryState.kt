package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

@Stable
internal class GoalEntryState(open: Boolean = false) {
    var open by mutableStateOf(open)
        private set
    var entryId = 0L
        private set

    fun show(): Boolean {
        if (open) return false
        entryId++
        open = true
        return true
    }
    fun dismiss() { entryId++; open = false }
    fun back() = dismiss()

    /** A slow write must not close a subsequently reopened sheet. */
    fun saved(id: Long) { if (entryId == id) dismiss() }

    companion object {
        val Saver = listSaver<GoalEntryState, Any>(
            save = { listOf(it.open, it.entryId) },
            restore = { GoalEntryState(it[0] as Boolean).apply { entryId = it[1] as Long } },
        )
    }
}

@Composable
internal fun rememberGoalEntryState() = rememberSaveable(saver = GoalEntryState.Saver) { GoalEntryState() }
