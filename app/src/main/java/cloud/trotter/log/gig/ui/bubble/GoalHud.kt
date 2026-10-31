package cloud.trotter.log.gig.ui.bubble

import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.feature.bubble.hud.goal.GoalSheet
import cloud.trotter.log.gig.feature.bubble.hud.goal.GoalSheetModel
import kotlin.math.roundToInt

@Stable
internal class GoalHud(private val viewModel: BubbleViewModel, private val entry: GoalEntryState,
    private val closeLog: () -> Unit) : SheetPort {
    override val key = EntrySheet.GOAL
    override val entryId get() = entry.entryId
    override val completed by lazy { viewModel.goalActions.committed }
    override fun completionHandled() = viewModel.goalActions.committedHandled()
    override fun show() = openSheet()

    override val open get() = entry.open
    fun openSheet() { closeLog(); if (entry.show()) viewModel.goalActions.reopen() }
    override fun back() = entry.back()
    override fun dismiss() = entry.dismiss()

    @Composable
    fun Sheet() {
        val goal by viewModel.dailyGoal.collectAsStateWithLifecycle()
        val earned by viewModel.todayEarned.collectAsStateWithLifecycle()
        val actions = viewModel.goalActions
        val busy by actions.busy.collectAsStateWithLifecycle()
        val failed by actions.failed.collectAsStateWithLifecycle()
        GoalSheet(GoalSheetModel(goal?.roundToInt(), earned, busy, failed),
            onSet = { actions.set(it) },
            onClear = { actions.clear(entry.entryId) },
            onPreset = { actions.set(it, entry.entryId) })
    }
}

@Composable
internal fun rememberGoalHud(viewModel: BubbleViewModel, closeLog: () -> Unit): GoalHud {
    val entry = rememberGoalEntryState()
    val hud = remember(viewModel, entry) { GoalHud(viewModel, entry, closeLog) }
    return hud
}
