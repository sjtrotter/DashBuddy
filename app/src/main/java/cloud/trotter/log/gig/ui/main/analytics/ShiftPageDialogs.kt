package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.SessionDetail

/** Which correction dialog is open on the shift page, and its hoisted fields. One per shift. */
@Stable
internal class ShiftPageDialogState {
    var showReport by mutableStateOf(false)
    var reportAmount by mutableStateOf("")
    var reportNote by mutableStateOf("")
    var showMiles by mutableStateOf(false)
    var milesText by mutableStateOf("")
    var showAdd by mutableStateOf(false)
    var adjustTarget by mutableStateOf<DeliveryRecord?>(null)

    fun openReport(detail: SessionDetail) {
        reportAmount = detail.session.reportedEarnings?.toString().orEmpty()
        reportNote = ""
        showReport = true
    }

    fun openMiles(detail: SessionDetail) {
        milesText = milesFieldText(detail.session.miles)
        showMiles = true
    }
}

/** The page's correction dialogs: set the app total, correct miles, add a missed delivery, fix a delivery. */
@Composable
internal fun ShiftPageDialogs(detail: SessionDetail, state: ShiftPageDialogState, actions: ShiftPageActions) {
    if (state.showReport) {
        SessionReportDialog(
            session = detail.session,
            amount = state.reportAmount,
            onAmountChange = { state.reportAmount = it },
            note = state.reportNote,
            onNoteChange = { state.reportNote = it },
            onConfirm = { operation, value ->
                actions.onCorrectSessionReport(operation, value, state.reportNote.trim().ifEmpty { null })
                state.showReport = false
            },
            onDismiss = { state.showReport = false },
        )
    }
    if (state.showMiles) {
        SessionMilesDialog(
            session = detail.session,
            deliveryMiles = detail.deliveryMiles,
            miles = state.milesText,
            onMilesChange = { state.milesText = it },
            onSave = { miles ->
                actions.onCorrectShiftMiles(miles)
                state.showMiles = false
            },
            onRestore = {
                actions.onCorrectShiftMiles(null)
                state.showMiles = false
            },
            onDismiss = { state.showMiles = false },
        )
    }
    if (state.showAdd) {
        AddMissedDeliveryDialog(
            onDismiss = { state.showAdd = false },
            onConfirm = { pay, tip, cashTip, store, note ->
                actions.onAddManualDelivery(pay, tip, cashTip, store, note)
                state.showAdd = false
            },
        )
    }
    state.adjustTarget?.let { target ->
        AdjustDeliveryDialog(
            target = target,
            onDismiss = { state.adjustTarget = null },
            onConfirm = { store, newPay, newTip, newCashTip, newMiles, note ->
                actions.onAdjustDelivery(target.eventSequenceId, store, newPay, newTip, newCashTip, newMiles, note)
                state.adjustTarget = null
            },
            onUnassign = {
                actions.onUnassignDelivery(target.eventSequenceId)
                state.adjustTarget = null
            },
        )
    }
}
