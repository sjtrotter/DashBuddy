package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.analytics.AnalyticsRepository
import cloud.trotter.dashbuddy.core.data.analytics.CorrectionRepository
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.hud.cashtip.CashTipFailure
import cloud.trotter.dashbuddy.feature.bubble.hud.undo.UndoStripModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.time.Clock

/** One append per confirmed tap. A projection lag never loses an earlier committed tip. */
internal class CashTipActions(
    private val scope: CoroutineScope,
    private val corrections: CorrectionRepository,
    private val analytics: AnalyticsRepository,
    private val clock: Clock,
) {
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val failure = MutableStateFlow(CashTipFailure.NONE)
    val failed = failure.asStateFlow()
    private val events = MutableSharedFlow<CashTipCommit>(replay = 1, extraBufferCapacity = 1)
    val committed: SharedFlow<CashTipCommit> = events.asSharedFlow()
    private val undoState = MutableStateFlow<CashTipCommit?>(null)
    val undo = undoState.asStateFlow()
    // Keyed by the durable append id; only the latest expectation for each delivery stands.
    // Kept past the undo deadline until the projector folds that append's sequence.
    private val pendingState = MutableStateFlow<Map<Long, CashTipCommit>>(emptyMap())
    val pending = pendingState.asStateFlow()
    private var lastWatermark = 0L

    fun reopen() { failure.value = CashTipFailure.NONE }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun committedHandled() { events.resetReplayCache() }

    fun commit(deliveryId: Long, sessionId: String?, storeName: String?, enteredCents: Long, entryId: Long = 0) {
        if (writing.value || enteredCents == 0L || sessionId == null) return
        write {
            val pending = pendingState.value.values.find { it.deliveryId == deliveryId }
            // A cleared expectation means the projector transaction is durable, even if the UI's
            // separately collected rows still lag. Read the database, never a display snapshot.
            val delivery = if (pending == null) analytics.deliveryById(deliveryId) else null
            val previous = pending?.expectedCashTipCents ?: Formats.cents(delivery?.cashTip ?: 0.0)
            val next = try { Math.addExact(previous, enteredCents) } catch (_: ArithmeticException) { return@write }
            if (next < 0L) {
                failure.value = CashTipFailure.BELOW_ZERO
                return@write
            }
            val sequenceId = corrections.adjustDelivery(deliveryId, sessionId, newCashTip = next / 100.0)
            val model = CashTipCommit(UndoStripModel(enteredCents, storeName, sequenceId,
                clock.millis() + UndoStripModel.WINDOW_MS, HudNoticeOrder.next()), deliveryId,
                sessionId, pending?.taskId ?: delivery?.taskId.orEmpty(), previous, next, entryId)
            failure.value = CashTipFailure.NONE
            expect(model)
            undoState.value = model
            events.emit(model)
        }
    }

    fun undo(model: CashTipCommit) {
        if (writing.value || undoState.value != model || (!model.strip.failed && clock.millis() >= model.strip.deadline)) return
        // Mark busy synchronously: a double tap cannot append two reversals. Retain for retry.
        undoState.value = model.copy(strip = model.strip.copy(busy = true, failed = false))
        write(reportToSheet = false, onFailure = {
            // Re-arm the strip even when a retry fails before Compose observes the busy state.
            undoState.value = model.copy(strip = model.strip.copy(busy = false, failed = true,
                token = HudNoticeOrder.next()))
        }) {
            val sequenceId = corrections.adjustDelivery(model.deliveryId, model.sessionId,
                newCashTip = model.previousCashTipCents / 100.0)
            expect(model.copy(strip = model.strip.copy(committedSequenceId = sequenceId,
                amountCents = -model.strip.amountCents, busy = false, failed = false),
                expectedCashTipCents = model.previousCashTipCents))
            undoState.value = null
        }
    }

    fun undoExpired(model: CashTipCommit) {
        if (undoState.value == model && !model.strip.busy && !model.strip.failed) undoState.value = null
    }

    fun acknowledgeThrough(watermarkSequenceId: Long) {
        lastWatermark = maxOf(lastWatermark, watermarkSequenceId)
        pendingState.value = pendingState.value.filterValues { pending ->
            pending.strip.committedSequenceId > lastWatermark
        }
    }

    private fun expect(model: CashTipCommit) {
        if (model.strip.committedSequenceId <= lastWatermark) return
        pendingState.value = pendingState.value.filterValues { it.deliveryId != model.deliveryId } +
            (model.strip.committedSequenceId to model)
    }

    private fun write(reportToSheet: Boolean = true, onFailure: () -> Unit = {}, action: suspend () -> Unit) {
        writing.value = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (reportToSheet) failure.value = CashTipFailure.WRITE
                onFailure()
                Timber.tag("CashTip").w("%s", error.javaClass.simpleName)
            } finally { writing.value = false }
        }
    }
}
