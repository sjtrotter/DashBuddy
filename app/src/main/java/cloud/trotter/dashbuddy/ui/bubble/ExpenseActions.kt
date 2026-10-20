package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.LedgerRepository
import cloud.trotter.dashbuddy.domain.analytics.ExpenseCategory
import cloud.trotter.dashbuddy.feature.bubble.hud.expense.*
import cloud.trotter.dashbuddy.feature.bubble.hud.keypad.DecimalEntry
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Clock

internal data class ExpenseCommit(val strip: UndoStripModel, val entryId: Long)

/** Writes finish even if the bubble disappears. Only durable append ids arm Undo. */
internal class ExpenseActions(scope: CoroutineScope, private val ledger: LedgerRepository,
    private val clock: Clock) {
    private val failure = MutableStateFlow(ExpenseFailure.NONE)
    val failed = failure.asStateFlow()
    private val undoState = MutableStateFlow<ExpenseCommit?>(null)
    val undo = undoState.asStateFlow()
    private val writer = EntryWriter<ExpenseCommit>(scope, "Expense") { failure.value = ExpenseFailure.WRITE }
    val busy = writer.busy
    val committed = writer.committed

    fun reopen() { failure.value = ExpenseFailure.NONE }
    fun committedHandled() = writer.committedHandled()

    fun logExpense(owner: String?, category: ExpenseCategory, cents: Long, label: String?, entryId: Long = 0, vehicleId: String? = null) {
        if (busy.value || cents <= 0) return
        val note = label?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.substring(0, it.offsetByCodePoints(0, minOf(it.codePointCount(0, it.length), ExpenseLabel.MAX_CODE_POINTS)))
        }
        commit(ExpensePick.Expense(category), cents, note, entryId) { amount, now ->
            ledger.logExpense(owner, category, amount, note, now,
                vehicleId.takeIf { category == ExpenseCategory.CAR_CARE })
        }
    }

    fun logFuel(owner: String?, cents: Long, gallons: String, gpsMiles: Double?, entryId: Long = 0, vehicleId: String? = null) {
        if (busy.value || cents <= 0) return
        // Validate the same bounded buffer as the UI; parse once at the write boundary.
        val entry = runCatching { DecimalEntry(gallons) }.getOrNull() ?: return
        if (!entry.positive) return
        val parsedGallons = gallons.toDoubleOrNull() ?: return
        commit(ExpensePick.Fuel, cents, null, entryId) { amount, now ->
            ledger.logFuel(owner, amount, parsedGallons, gpsMiles, now, vehicleId)
        }
    }

    private fun commit(pick: ExpensePick, cents: Long, label: String?, entryId: Long,
        append: suspend (Double, Long) -> Long) {
        writer.write {
            failure.value = ExpenseFailure.NONE
            val sequenceId = append(cents / 100.0, clock.millis())
            val commit = ExpenseCommit(UndoStripModel(cents, label, sequenceId,
                clock.millis() + UndoStripModel.WINDOW_MS, HudNoticeOrder.next(),
                whatLabel = pick.labelResource, whereFallback = null), entryId)
            undoState.value = commit
            writer.commit(commit)
        }
    }

    fun undo(commit: ExpenseCommit) {
        if (busy.value || undoState.value != commit ||
            (!commit.strip.failed && clock.millis() >= commit.strip.deadline)) return
        undoState.value = commit.copy(strip = commit.strip.copy(busy = true, failed = false))
        writer.write(reportFailure = false, onFailure = {
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
}
