package cloud.trotter.log.gig.ui.bubble

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.feature.bubble.hud.cashtip.*
import cloud.trotter.log.gig.feature.bubble.hud.undo.*
import kotlinx.coroutines.flow.flowOf

@Stable
internal class CashTipHud(
    private val viewModel: BubbleViewModel,
    private val entry: CashTipEntryState,
    private val closeLog: () -> Unit,
) {
    private var sessionId by mutableStateOf<String?>(null)
    private var rows by mutableStateOf<List<DeliveryRecord>>(emptyList())
    val open: Boolean get() = entry.open

    fun openSheet() {
        closeLog()
        viewModel.cashTipActions.reopen()
        entry.show(rows.maxWithOrNull(compareBy<DeliveryRecord> { it.completedAt }.thenBy { it.eventSequenceId })?.eventSequenceId, sessionId)
    }
    fun dismiss() { entry.open = false }
    fun back() { entry.back() }

    @Composable
    fun Observe(dashId: String?) {
        val source = remember(viewModel, dashId) { dashId?.let(viewModel::cashTipDeliveries) ?: flowOf(emptyList()) }
        // Re-key the collected state so an old dash's rows can never enter a new dash's picker.
        key(dashId) {
            val deliveries by source.collectAsStateWithLifecycle(emptyList())
            SideEffect {
                if (entry.open && entry.dashId != dashId) dismiss()
                sessionId = dashId
                if (entry.open && entry.selectedDeliveryId == null) {
                    entry.selectedDeliveryId = deliveries.maxWithOrNull(
                        compareBy<DeliveryRecord> { it.completedAt }.thenBy { it.eventSequenceId })?.eventSequenceId
                }
                rows = deliveries
            }
        }
        LaunchedEffect(viewModel) {
            viewModel.projectionWatermark.collect { watermark ->
                watermark?.let(viewModel.cashTipActions::acknowledgeThrough)
            }
        }
        LaunchedEffect(viewModel, entry) {
            viewModel.cashTipActions.committed.collect { committed ->
                if (committed.entryId == entry.entryId) dismiss()
                viewModel.cashTipActions.committedHandled()
            }
        }
    }

    @Composable
    fun Sheet() {
        val actions = viewModel.cashTipActions
        val busy by actions.busy.collectAsStateWithLifecycle()
        val failure by actions.failed.collectAsStateWithLifecycle()
        val pending by actions.pending.collectAsStateWithLifecycle()
        // Use the last confirmed value during projection lag, including removal validation.
        val pendingCents = pending.values.associate { it.deliveryId to it.expectedCashTipCents }
        Column(Modifier.fillMaxSize()) {
            CashTipSheet(CashTipSheetModel(rows, entry.selectedDeliveryId, entry.totalCents, entry.sign,
                entry.page, entry.otherAmount, busy, failure, pendingCents),
                onSelectDelivery = entry::select, onExpand = { entry.page = CashTipPage.EXPANDED },
                onBack = ::back, onSign = { entry.sign = it }, onAmount = entry::add,
                onOther = entry::other, onDigit = entry::digit, onPoint = entry::point,
                onBackspace = entry::backspace, onDoneOther = entry::doneOther,
                onCommit = {
                    rows.find { it.eventSequenceId == entry.selectedDeliveryId }?.let { row ->
                        actions.commit(row.eventSequenceId, row.sessionId, row.storeName,
                            if (entry.sign == CashTipSign.MINUS) -entry.totalCents else entry.totalCents, entry.entryId)
                    }
                })
        }
    }

    @Composable
    fun UndoVisible(): Boolean {
        val strip by viewModel.cashTipActions.undo.collectAsStateWithLifecycle()
        val visible = undoStripVisible(strip?.strip)
        if (!visible) strip?.let { model -> SideEffect { viewModel.cashTipActions.undoExpired(model) } }
        return visible
    }

    @Composable
    fun Undo() {
        val strip by viewModel.cashTipActions.undo.collectAsStateWithLifecycle()
        strip?.let { model -> UndoStrip(model.strip) { viewModel.cashTipActions.undo(model) } }
    }

    @Composable
    fun Cue(): Boolean {
        val pending by viewModel.cashTipActions.pending.collectAsStateWithLifecycle()
        val visible = UndoVisible()
        return visible || pending.values.any { it.sessionId == sessionId }
    }

    @Composable
    fun Pending(taskId: String?): Long? {
        val pending by viewModel.cashTipActions.pending.collectAsStateWithLifecycle()
        return CashTipPending.footAmountCents(
            pending.values.find { it.sessionId == sessionId && it.taskId == taskId })
    }
}

@Composable
internal fun rememberCashTipHud(viewModel: BubbleViewModel, dashId: String?, closeLog: () -> Unit): CashTipHud {
    val entry = rememberCashTipEntryState()
    val hud = remember(viewModel, entry) { CashTipHud(viewModel, entry, closeLog) }
    hud.Observe(dashId)
    return hud
}
