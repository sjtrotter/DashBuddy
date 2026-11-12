package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.receipts.MatchLock
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatch
import cloud.trotter.log.gig.domain.analytics.receipts.ReceiptMatchPlan
import cloud.trotter.log.gig.domain.analytics.receipts.SavedReceipt
import cloud.trotter.log.gig.domain.analytics.receipts.ShiftReceipts
import cloud.trotter.log.gig.domain.analytics.timeline.PaySource
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.domain.format.formatClockTime
import java.util.UUID

private const val SEPARATOR = " · "

internal const val RECEIPT_SHEET_CONFIRM_TAG = "receipt_sheet_confirm"
internal const val RECEIPT_SHEET_NOT_SURE_TAG = "receipt_sheet_not_sure"

/** The ticked delivery ids, saved across recreation. */
private val longSetSaver = listSaver<MutableState<Set<Long>>, Long>(
    save = { it.value.toList() },
    restore = { mutableStateOf(it.toSet()) },
)

private fun cents(amount: Long): String = Formats.money(amount / 100.0)

/**
 * "Which deliveries did this receipt pay for?" (#1599): every delivery of the shift, the receipt's own delivery (and on
 * a one-offer job its other drops) ticked and marked Suggested, a locked delivery shown disabled with its reason, and
 * the preview ([ReceiptMatch.plan]) under the list. The one sheet that shows estimates, since it replaces them.
 * Confirm sends the plan exactly as shown; nothing writes on open, Back, a swipe, a scrim tap or Not sure. The
 * selection is UI state; every figure derives from [receipts] (Principle 1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReceiptMatchSheet(
    receipt: SavedReceipt,
    receipts: ShiftReceipts,
    /** `MoneyCheck.of(detail)?.appTotal`: only an independent total. */
    appTotal: Double?,
    appName: String,
    onConfirm: (commandId: String, plan: ReceiptMatchPlan) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    // One per opening: a second write of the same Confirm is the fold's counted skip.
    val commandId = rememberSaveable(receipt.evidenceId) { UUID.randomUUID().toString() }
    var selected by rememberSaveable(receipt.evidenceId, saver = longSetSaver) {
        mutableStateOf(ReceiptMatch.suggestion(receipt, receipts.deliveries, receipts.active))
    }
    val suggested = remember(receipt, receipts) { ReceiptMatch.suggestion(receipt, receipts.deliveries, receipts.active) }
    val plan = remember(selected, receipt, receipts) {
        ReceiptMatch.plan(receipt, receipts.deliveries, receipts.active, selected, receipts.saved)
    }
    val rows = remember(receipts) { receipts.deliveries.sortedWith(ReceiptMatch.DISPLAY_ORDER) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            Text(
                stringResource(R.string.receipt_match_title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
            )
            Text(
                stringResource(R.string.receipt_match_receipt_format, cents(receipt.totalCents), formatClockTime(receipt.firstSeenAt)),
                Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text2,
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                rows.forEach { d ->
                    val seq = d.eventSequenceId
                    val open = ReceiptMatch.openTo(receipt.evidenceId, d, receipts.deliveries, receipts.active, receipts.saved)
                    ReceiptRow(
                        delivery = d,
                        jobDeliveries = receipts.deliveries.count { it.jobId == d.jobId },
                        checked = open && seq in selected,
                        open = open,
                        suggested = seq in suggested,
                        outcome = rowOutcome(receipt, d, plan, receipts),
                        onToggle = { on -> selected = if (on) selected + seq else selected - seq },
                    )
                }
            }
            plan?.let { p ->
                Text(
                    stringResource(R.string.receipt_match_delivery_pay_format, cents(p.shiftPayBeforeCents), cents(p.shiftPayAfterCents)),
                    Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                )
                appTotal?.let {
                    Text(
                        stringResource(R.string.receipt_match_app_total_format, appName, Formats.money(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text2,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(RECEIPT_SHEET_NOT_SURE_TAG)) {
                    Text(stringResource(R.string.receipt_match_not_sure))
                }
                Button(
                    onClick = { plan?.let { onConfirm(commandId, it) } },
                    enabled = plan != null,
                    modifier = Modifier.padding(start = 8.dp).testTag(RECEIPT_SHEET_CONFIRM_TAG),
                ) {
                    Text(stringResource(R.string.receipt_match_confirm))
                }
            }
        }
    }
}

/** A row's third line: what the match does to it, or why it cannot take the receipt; null for an unticked open row. */
@Composable
private fun rowOutcome(receipt: SavedReceipt, d: DeliveryRecord, plan: ReceiptMatchPlan?, receipts: ShiftReceipts): String? {
    val seq = d.eventSequenceId
    plan?.covered?.firstOrNull { it.deliveryEventSequenceId == seq }?.newPayCents?.let {
        return stringResource(R.string.receipt_match_new_pay_format, cents(it))
    }
    if (plan?.cleared?.any { it.deliveryEventSequenceId == seq } == true) return stringResource(R.string.receipt_match_estimate_removed)
    // A delivery of a one-offer job another receipt already pays reads "Matched to receipt $X", that receipt's total.
    return when (val lock = ReceiptMatch.lockFor(receipt.evidenceId, d, receipts.deliveries, receipts.active, receipts.saved)) {
        null -> null
        MatchLock.PaidByReceipt -> stringResource(R.string.receipt_match_paid_by_receipt)
        MatchLock.ChangedByYou -> stringResource(R.string.receipt_match_changed_by_you)
        is MatchLock.MatchedTo -> stringResource(
            R.string.receipt_match_matched_to_format,
            receipts.totalCentsOf(lock.evidenceId)?.let(::cents) ?: EMPTY_VALUE,
        )
    }
}

/** One delivery: a checkbox row that TalkBack reads as one control, three lines and a trailing "Suggested". */
@Composable
private fun ReceiptRow(
    delivery: DeliveryRecord,
    jobDeliveries: Int,
    checked: Boolean,
    open: Boolean,
    suggested: Boolean,
    outcome: String?,
    onToggle: (Boolean) -> Unit,
) {
    val c = AppTheme.colors
    val source = PaySource.of(delivery.payBasis, delivery.realizedPay, jobDeliveries)
    val pay = delivery.realizedPay?.let { moneyText(it, source.estimated) } ?: EMPTY_VALUE
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = open, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 8.dp)
            .testTag("receipt_row_${delivery.eventSequenceId}"),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = open)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                listOf(formatClockTime(delivery.completedAt), storesText(delivery.storeName)).joinToString(SEPARATOR),
                style = MaterialTheme.typography.bodyMedium,
                color = if (open) c.text else c.text2,
            )
            Text(listOf(sourceText(source), pay).joinToString(SEPARATOR), style = MaterialTheme.typography.bodyMedium, color = c.text2)
            outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (open) c.text else c.text2) }
        }
        if (suggested) {
            Text(
                stringResource(R.string.receipt_match_suggested),
                Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = c.text2,
            )
        }
    }
}
