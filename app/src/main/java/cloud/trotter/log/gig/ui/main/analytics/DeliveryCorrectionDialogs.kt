package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.PayBasis

/**
 * Parse an optional numeric field (F9): blank ⇒ null (unchanged); a non-blank field ⇒ its parsed
 * value ONLY when it is **finite** (a pasted `1e999` parses to `Infinity`, which the codec would then
 * reject with a crash, #705 F4a) — else null. This owns ONLY finiteness + parseability; **range**
 * validation (> 0 for money, ≥ 0 for tip/cash/miles) belongs to the `isValid*`/`isBlankOr*` checks
 * below. Used for pay/tip/cash/miles alike (it is not money-specific), so it is deliberately un-named
 * for money.
 */
private fun optionalNumber(text: String): Double? =
    text.takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

/** A finite, strictly-positive money value (F4a: `isFinite` rejects a pasted `1e999`). */
private fun String.isValidMoney(): Boolean = toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true
private fun String.isBlankOrValidMoney(): Boolean = isBlank() || isValidMoney()

/** Blank, or a finite non-negative number — for tip/cash/miles fields (0 is a valid entry; F4a). */
private fun String.isBlankOrNonNegative(): Boolean =
    isBlank() || (toDoubleOrNull()?.let { it.isFinite() && it >= 0.0 } == true)

/**
 * Add-a-missed-delivery dialog (#650/#688) — store (optional), pay (required, > 0), tip (optional,
 * ≥ 0 when present), cash tip (optional, ≥ 0 when present), note (optional). Confirm stays disabled
 * until pay is valid and any entered tip/cash is valid. Stateless w/ hoisted local field state.
 */
@Composable
internal fun AddMissedDeliveryDialog(
    onDismiss: () -> Unit,
    onConfirm: (pay: Double, tip: Double?, cashTip: Double?, storeName: String?, note: String?) -> Unit,
) {
    var store by remember { mutableStateOf("") }
    var pay by remember { mutableStateOf("") }
    var tip by remember { mutableStateOf("") }
    var cashTip by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val valid = pay.isValidMoney() && tip.isBlankOrNonNegative() && cashTip.isBlankOrNonNegative()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_detail_add_missed_delivery)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(store, { store = it }, stringResource(R.string.session_detail_field_store_optional), numeric = false)
                MoneyField(pay, { pay = it }, stringResource(R.string.session_detail_field_pay))
                MoneyField(tip, { tip = it }, stringResource(R.string.session_detail_field_tip_included))
                MoneyField(cashTip, { cashTip = it }, stringResource(R.string.session_detail_field_cash_tip_optional))
                MoneyField(note, { note = it }, stringResource(R.string.session_detail_field_note_optional), numeric = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        pay.toDouble(),
                        optionalNumber(tip),
                        optionalNumber(cashTip),
                        store.trim().takeIf { it.isNotBlank() },
                        note.trim().takeIf { it.isNotBlank() },
                    )
                },
            ) { Text(stringResource(R.string.session_detail_add_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_detail_cancel_button)) } },
    )
}

/**
 * Adjust-delivery dialog (#688) — the one multi-field editor: Store name / Pay / Tip / Cash tip /
 * Miles / Note. Each money/miles field is prefilled cent-faithfully via `toString()` (NOT
 * `Formats.decimal`, which rounds and would drop cents from the round-trip). **Blank = unchanged**
 * (a store name can't be cleared to empty — intended; clearing a value is not supported). Only fields
 * whose PARSED value differs from the row's current value ship in the event; a blank/unchanged field
 * → null. Save stays disabled until every entered field is valid AND at least one parsed field
 * differs (or a note is present, #688 F6 — a note-only annotation is a valid edit).
 */
@Composable
internal fun AdjustDeliveryDialog(
    target: DeliveryRecord,
    onDismiss: () -> Unit,
    onConfirm: (
        newStoreName: String?,
        newPay: Double?,
        newTip: Double?,
        newCashTip: Double?,
        newMiles: Double?,
        note: String?,
    ) -> Unit,
    onUnassign: () -> Unit,
) {
    // All field state is keyed on [target] (F6): a mid-edit Room re-emission that produces a DIFFERENT
    // target snapshot re-initialises the fields from it, so the change-diff below never compares an
    // edit against a stale prefill. An equal re-emission (data-class equality) leaves the edit intact.
    var store by remember(target) { mutableStateOf(target.storeName ?: "") }
    var pay by remember(target) { mutableStateOf(target.realizedPay?.toString() ?: "") }
    var tip by remember(target) { mutableStateOf(target.tip?.toString() ?: "") }
    var cashTip by remember(target) { mutableStateOf(target.cashTip?.toString() ?: "") }
    var miles by remember(target) { mutableStateOf(target.realizedMiles?.toString() ?: "") }
    var note by remember(target) { mutableStateOf("") }

    // FIX 5: a SUSPECT_FULL_RECEIPT row's money was nulled as a #653 double-count guard — editing pay
    // back onto it re-opens the double count (its siblings keep their shares). Disable the Pay field
    // (the orchestrator also blocks it as a second gate); tip is prefilled null on such a row anyway.
    val payLocked = target.payBasis == PayBasis.SUSPECT_FULL_RECEIPT

    // Parsed values (blank ⇒ null ⇒ unchanged; F9 optionalNumber rejects a non-finite paste).
    val storeParsed = store.trim().takeIf { it.isNotBlank() }
    val payParsed = optionalNumber(pay)
    val tipParsed = optionalNumber(tip)
    val cashParsed = optionalNumber(cashTip)
    val milesParsed = optionalNumber(miles)
    val noteParsed = note.trim().takeIf { it.isNotBlank() }

    // Per-field validity: pay > 0 (when present); tip/cash/miles ≥ 0 (when present).
    val payValid = pay.isBlankOrValidMoney()
    val tipValid = tip.isBlankOrNonNegative()
    val cashValid = cashTip.isBlankOrNonNegative()
    val milesValid = miles.isBlankOrNonNegative()

    // Changed-field detection on PARSED values (prefill round-trips via toString, so an untouched
    // field parses back equal and is not sent). Store compares TRIMMED-vs-TRIMMED (F6) so a
    // machine-parsed trailing-space store name doesn't ship a phantom newStoreName on a cash-only edit.
    val storeChanged = storeParsed != null && storeParsed != (target.storeName?.trim() ?: "")
    val payChanged = payParsed != null && payParsed != target.realizedPay
    val tipChanged = tipParsed != null && tipParsed != target.tip
    val cashChanged = cashParsed != null && cashParsed != target.cashTip
    val milesChanged = milesParsed != null && milesParsed != target.realizedMiles

    val anyChange = storeChanged || payChanged || tipChanged || cashChanged || milesChanged || noteParsed != null
    val valid = payValid && tipValid && cashValid && milesValid && anyChange

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_detail_adjust_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(store, { store = it }, stringResource(R.string.session_detail_field_store_name), numeric = false)
                MoneyField(
                    pay, { pay = it }, stringResource(R.string.session_detail_field_pay),
                    enabled = !payLocked,
                    supportingText = if (payLocked) stringResource(R.string.session_detail_field_pay_locked_supporting) else null,
                )
                MoneyField(tip, { tip = it }, stringResource(R.string.session_detail_field_tip_included), enabled = !payLocked)
                MoneyField(cashTip, { cashTip = it }, stringResource(R.string.session_detail_field_cash_tip))
                MoneyField(miles, { miles = it }, stringResource(R.string.session_detail_stat_miles))
                MoneyField(note, { note = it }, stringResource(R.string.session_detail_field_note_optional), numeric = false)
                // #660 piece 2 undo: only a driver-assigned row can be removed back to the bucket (a
                // machine row is never movable by the projector guard). Attribution-only — never re-prices.
                // A cash-bearing row's unassign is REFUSED by the projector's F3 sessionful-cash guard
                // (a null-session cash row would reach net but not gross), so disable the action with a
                // hint rather than showing a control whose tap silently no-ops (#660 review).
                if (target.sessionAssigned) {
                    val cashLocked = target.cashTip != null
                    Column(Modifier.align(Alignment.Start)) {
                        TextButton(onClick = onUnassign, enabled = !cashLocked) {
                            Text(stringResource(R.string.session_detail_remove_from_dash))
                        }
                        if (cashLocked) {
                            Text(
                                text = stringResource(R.string.session_detail_remove_cash_locked_supporting),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTheme.colors.neutral,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        if (storeChanged) storeParsed else null,
                        if (payChanged) payParsed else null,
                        if (tipChanged) tipParsed else null,
                        if (cashChanged) cashParsed else null,
                        if (milesChanged) milesParsed else null,
                        noteParsed,
                    )
                },
            ) { Text(stringResource(R.string.session_detail_save_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_detail_cancel_button)) } },
    )
}

@Composable
private fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    numeric: Boolean = true,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = Modifier.fillMaxWidth(),
    )
}
