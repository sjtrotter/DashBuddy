package cloud.trotter.dashbuddy.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.*
import kotlinx.coroutines.flow.flowOf

@Stable
internal class ExpenseHud(private val viewModel: BubbleViewModel, private val entry: ExpenseEntryState,
    private val closeLog: () -> Unit) {
    val open get() = entry.open
    val compact get() = entry.page == ExpensePage.SUBCARD
    fun openSubcard() { closeLog(); viewModel.expenseActions.reopen(); entry.show() }
    fun back() { entry.back() }
    fun dismiss() { entry.open = false; entry.keyboardOpen = false }

    @Composable
    fun Observe() {
        LaunchedEffect(viewModel, entry) {
            viewModel.expenseActions.committed.collect { commit ->
                if (commit.entryId == entry.entryId) dismiss()
                viewModel.expenseActions.committedHandled()
            }
        }
    }

    @Composable
    fun Sheet() {
        val actions = viewModel.expenseActions
        val busy by actions.busy.collectAsStateWithLifecycle()
        if (compact) {
            ExpenseSubcard { pick -> if (!busy) { actions.reopen(); entry.select(pick) } }
            return
        }
        val failure by actions.failed.collectAsStateWithLifecycle()
        // Keep the GPS source subscribed while entering; read its current value at the tap.
        viewModel.gpsTotalMiles.collectAsStateWithLifecycle()
        val category = (entry.pick as? ExpensePick.Expense)?.category
        val labels = remember(category, viewModel) { category?.let(viewModel::recentLabels) ?: flowOf(emptyList()) }
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = LocalFocusManager.current
        fun closeKeyboard() { entry.keyboardOpen = false; focus.clearFocus(); keyboard?.hide() }
        key(category) {
            val recent by labels.collectAsStateWithLifecycle(emptyList())
            Column(Modifier.fillMaxSize()) {
                ExpenseSheet(ExpenseSheetModel(entry.pick, entry.totalCents, entry.gallonsDigits, entry.label,
                    recent, entry.activeField, busy, failure, entry.keyboardOpen, entry.totalDigits),
                    onBack = { closeKeyboard(); back() },
                    onField = { closeKeyboard(); entry.activeField = it },
                    onLabel = { entry.label = it }, onSelectLabel = { closeKeyboard(); entry.label = it }, onNewLabel = { entry.label = ""; entry.keyboardOpen = true },
                    onKeyboardDone = ::closeKeyboard, onDigit = entry::digit, onPoint = entry::point,
                    onBackspace = entry::backspace, onLog = {
                        closeKeyboard()
                        val owner = viewModel.expenseOwner()
                        when (val pick = entry.pick) {
                            ExpensePick.Fuel -> actions.logFuel(owner, entry.totalCents, entry.gallonsDigits,
                                viewModel.gpsTotalMiles.value, entry.entryId)
                            is ExpensePick.Expense -> actions.logExpense(owner, pick.category, entry.totalCents,
                                entry.label.takeIf { pick.hasLabel }, entry.entryId)
                        }
                    })
            }
        }
    }

    @Composable
    fun UndoVisible(): Boolean {
        val commit by viewModel.expenseActions.undo.collectAsStateWithLifecycle()
        val visible = undoStripVisible(commit?.strip)
        if (!visible) commit?.let { SideEffect { viewModel.expenseActions.undoExpired(it) } }
        return visible
    }
    @Composable fun Cue(): Boolean = UndoVisible()
    @Composable fun Undo() {
        val commit by viewModel.expenseActions.undo.collectAsStateWithLifecycle()
        commit?.let { UndoStrip(it.strip) { viewModel.expenseActions.undo(it) } }
    }
}

@Composable
internal fun rememberExpenseHud(viewModel: BubbleViewModel, closeLog: () -> Unit): ExpenseHud {
    val entry = rememberExpenseEntryState()
    val hud = remember(viewModel, entry) { ExpenseHud(viewModel, entry, closeLog) }
    hud.Observe()
    return hud
}
