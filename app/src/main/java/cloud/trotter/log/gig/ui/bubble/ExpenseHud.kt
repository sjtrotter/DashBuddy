package cloud.trotter.log.gig.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.domain.analytics.ExpenseCategory
import cloud.trotter.log.gig.feature.bubble.hud.vehicle.VehicleChip
import cloud.trotter.log.gig.feature.bubble.hud.expense.*
import cloud.trotter.log.gig.feature.bubble.hud.undo.*
import kotlinx.coroutines.flow.flowOf

@Stable
internal class ExpenseHud(private val viewModel: BubbleViewModel, private val entry: ExpenseEntryState,
    private val closeLog: () -> Unit, private val openVehicleAdd: () -> Unit) {
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
        val model by viewModel.vehicleChip.collectAsStateWithLifecycle()
        var menuOpen by rememberSaveable(entry.entryId) { mutableStateOf(false) }
        val vehicleChip: (@Composable () -> Unit)? = if (entry.pick == ExpensePick.Fuel ||
            entry.pick == ExpensePick.Expense(ExpenseCategory.CAR_CARE)) {
            {
                model?.let {
                    VehicleChip(it.forEntry(entry.vehicleId), menuOpen, { menuOpen = true },
                        { menuOpen = false }, onSelect = { id -> entry.vehicleId = id; menuOpen = false },
                        onAdd = { menuOpen = false; openVehicleAdd() })
                }
            }
        } else null
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
                ExpenseSheet(ExpenseSheetModel(entry.pick, entry.totalDigits, entry.gallonsDigits, entry.label,
                    recent, entry.activeField, busy, failure, entry.keyboardOpen),
                    onBack = { closeKeyboard(); back() },
                    onField = { closeKeyboard(); entry.activeField = it },
                    onLabel = { entry.label = it }, onSelectLabel = { closeKeyboard(); entry.label = it }, onNewLabel = { entry.newLabel(recent) },
                    onEditLabel = entry::editLabel,
                    onKeyboardDone = ::closeKeyboard, onDigit = entry::digit, onPoint = entry::point,
                    onBackspace = entry::backspace, vehicleChip = vehicleChip, onLog = {
                        closeKeyboard()
                        val owner = viewModel.expenseOwner()
                        val vehicleId = viewModel.vehicleChip.value?.forEntry(entry.vehicleId)?.activeId
                        when (val pick = entry.pick) {
                            ExpensePick.Fuel -> actions.logFuel(owner, entry.totalCents, entry.gallonsDigits,
                                viewModel.gpsTotalMiles.value, entry.entryId, vehicleId)
                            is ExpensePick.Expense -> actions.logExpense(owner, pick.category, entry.totalCents,
                                entry.label.takeIf { pick.hasLabel }, entry.entryId, vehicleId)
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
internal fun rememberExpenseHud(viewModel: BubbleViewModel, closeLog: () -> Unit, openVehicleAdd: () -> Unit): ExpenseHud {
    val entry = rememberExpenseEntryState()
    val hud = remember(viewModel, entry, closeLog, openVehicleAdd) { ExpenseHud(viewModel, entry, closeLog, openVehicleAdd) }
    hud.Observe()
    return hud
}
