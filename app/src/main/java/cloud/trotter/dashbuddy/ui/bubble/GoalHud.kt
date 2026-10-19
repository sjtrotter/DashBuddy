package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.feature.bubble.hud.goal.GoalSheet
import cloud.trotter.dashbuddy.feature.bubble.hud.goal.GoalSheetModel
import kotlin.math.roundToInt

@Stable
internal class GoalHud(private val viewModel: BubbleViewModel, private val entry: GoalEntryState,
    private val closeLog: () -> Unit) {
    val open get() = entry.open
    fun openSheet() { closeLog(); viewModel.goalActions.reopen(); entry.show() }
    fun back() = entry.back()
    fun dismiss() = entry.dismiss()

    @Composable
    fun Observe() {
        LaunchedEffect(viewModel, entry) {
            viewModel.goalActions.committed.collect { id ->
                entry.saved(id)
                viewModel.goalActions.committedHandled()
            }
        }
    }

    @Composable
    fun Sheet() {
        val goal by viewModel.dailyGoal.collectAsStateWithLifecycle()
        val profit by viewModel.todayProfit.collectAsStateWithLifecycle()
        val actions = viewModel.goalActions
        val busy by actions.busy.collectAsStateWithLifecycle()
        val failed by actions.failed.collectAsStateWithLifecycle()
        GoalSheet(GoalSheetModel(goal?.roundToInt(), profit, busy, failed),
            onSet = { actions.set(it) },
            onClear = { actions.clear(entry.entryId) },
            onPreset = { actions.set(it, entry.entryId) })
    }
}

@Composable
internal fun rememberGoalHud(viewModel: BubbleViewModel, closeLog: () -> Unit): GoalHud {
    val entry = rememberGoalEntryState()
    val hud = remember(viewModel, entry) { GoalHud(viewModel, entry, closeLog) }
    hud.Observe()
    return hud
}
