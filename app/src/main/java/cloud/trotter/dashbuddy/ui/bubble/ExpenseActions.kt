package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.DecimalEntry
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.Clock

internal data class ExpenseCommit(val strip: UndoStripModel, val entryId: Long)

/** Writes finish even if the bubble disappears. Only durable append ids arm Undo. */
internal class ExpenseActions(private val scope: CoroutineScope, private val ledger: LedgerRepository,
    private val clock: Clock) {
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val failure = MutableStateFlow(ExpenseFailure.NONE)
    val failed = failure.asStateFlow()
    private val undoState = MutableStateFlow<ExpenseCommit?>(null)
    val undo = undoState.asStateFlow()
    private val events = MutableSharedFlow<ExpenseCommit>(replay = 1, extraBufferCapacity = 1)
    val committed = events.asSharedFlow()

    fun reopen() { failure.value = ExpenseFailure.NONE }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun committedHandled() { events.resetReplayCache() }

    fun logExpense(owner: String?, category: ExpenseCategory, cents: Long, label: String?, entryId: Long = 0) {
        if (writing.value || cents <= 0) return
        val note = label?.trim()?.takeIf { it.isNotEmpty() }
        commit(ExpensePick.Expense(category), cents, note, entryId) { amount, now ->
            ledger.logExpense(owner, category, amount, note, now)
        }
    }

    fun logFuel(owner: String?, cents: Long, gallons: String, gpsMiles: Double?, entryId: Long = 0) {
        if (writing.value || cents <= 0) return
        // Validate the same bounded buffer as the UI; parse once at the write boundary.
        val entry = runCatching { DecimalEntry(gallons) }.getOrNull() ?: return
        if (!entry.positive) return
        val parsedGallons = gallons.toDoubleOrNull() ?: return
        commit(ExpensePick.Fuel, cents, null, entryId) { amount, now ->
            ledger.logFuel(owner, amount, parsedGallons, gpsMiles, now)
        }
    }

    private fun commit(pick: ExpensePick, cents: Long, label: String?, entryId: Long,
        append: suspend (Double, Long) -> Long) {
        write {
            val sequenceId = append(cents / 100.0, clock.millis())
            val commit = ExpenseCommit(UndoStripModel(cents, label, sequenceId,
                clock.millis() + UndoStripModel.WINDOW_MS, HudNoticeOrder.next(),
                whatLabel = pick.labelResource, whereFallback = null), entryId)
            undoState.value = commit
            events.emit(commit)
        }
    }

    fun undo(commit: ExpenseCommit) {
        if (writing.value || undoState.value != commit ||
            (!commit.strip.failed && clock.millis() >= commit.strip.deadline)) return
        undoState.value = commit.copy(strip = commit.strip.copy(busy = true, failed = false))
        write(reportToSheet = false, onFailure = {
            undoState.value = commit.copy(strip = commit.strip.copy(busy = false, failed = true,
                token = HudNoticeOrder.next()))
        }) {
            ledger.void(commit.strip.committedSequenceId, clock.millis())
            undoState.value = null
        }
    }

    fun undoExpired(commit: ExpenseCommit) {
        if (undoState.value == commit && !commit.strip.busy && !commit.strip.failed) undoState.value = null
    }

    private fun write(reportToSheet: Boolean = true, onFailure: () -> Unit = {}, action: suspend () -> Unit) {
        writing.value = true
        if (reportToSheet) failure.value = ExpenseFailure.NONE
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (reportToSheet) failure.value = ExpenseFailure.WRITE
                onFailure()
                Timber.tag("Expense").w("%s", error.javaClass.simpleName)
            } finally { writing.value = false }
        }
    }
}
