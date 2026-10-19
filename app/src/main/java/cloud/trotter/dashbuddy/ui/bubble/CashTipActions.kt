package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.Clock

/** One append per confirmed tap. A projection lag never loses an earlier committed tip. */
internal class CashTipActions(
    private val scope: CoroutineScope,
    private val corrections: CorrectionRepository,
    private val clock: Clock,
) {
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    private val events = MutableSharedFlow<CashTipCommit>(replay = 1, extraBufferCapacity = 1)
    val committed: SharedFlow<CashTipCommit> = events.asSharedFlow()
    private val undoState = MutableStateFlow<CashTipCommit?>(null)
    val undo = undoState.asStateFlow()
    // Kept past the undo deadline until the projection acknowledges each target value.
    private val pendingState = MutableStateFlow<Map<Long, CashTipCommit>>(emptyMap())
    val pending = pendingState.asStateFlow()

    fun reopen() { failure.value = false }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun committedHandled() { events.resetReplayCache() }

    fun previous(row: DeliveryRecord): Double = pendingState.value[row.eventSequenceId]?.expectedCashTip ?: row.cashTip ?: 0.0

    fun commit(row: DeliveryRecord, enteredCents: Long, entryId: Long = 0) {
        if (writing.value || enteredCents == 0L || row.sessionId == null) return
        val previous = previous(row)
        val next = previous + enteredCents / 100.0
        if (!next.isFinite() || next < 0.0) return
        write {
            val sequenceId = corrections.adjustDelivery(row.eventSequenceId, row.sessionId, newCashTip = next)
            val model = CashTipCommit(UndoStripModel(enteredCents, row.storeName, sequenceId,
                clock.millis() + UndoStripModel.WINDOW_MS, HudNoticeOrder.next()), row.eventSequenceId,
                row.sessionId, row.taskId, previous, next, entryId)
            pendingState.value = pendingState.value + (row.eventSequenceId to model)
            undoState.value = model
            events.emit(model)
        }
    }

    fun undo(model: CashTipCommit) {
        if (writing.value || undoState.value != model || clock.millis() >= model.strip.deadline) return
        // Consume synchronously: a double tap cannot append two reversals.
        undoState.value = null
        write {
            val sequenceId = corrections.adjustDelivery(model.deliveryId, model.sessionId, newCashTip = model.previousCashTip)
            pendingState.value = pendingState.value + (model.deliveryId to model.copy(
                strip = model.strip.copy(committedSequenceId = sequenceId), expectedCashTip = model.previousCashTip))
        }
    }

    fun undoExpired(model: CashTipCommit) {
        if (undoState.value == model) undoState.value = null
    }

    fun projected(rows: List<DeliveryRecord>) {
        pendingState.value = pendingState.value.filterValues { pending ->
            val row = rows.find { it.eventSequenceId == pending.deliveryId }
            row == null || kotlin.math.abs((row.cashTip ?: 0.0) - pending.expectedCashTip) >= 0.005
        }
    }

    private fun write(action: suspend () -> Unit) {
        writing.value = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { action() }
                failure.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.value = true
                Timber.tag("CashTip").w("%s", error.javaClass.simpleName)
            } finally { writing.value = false }
        }
    }
}
