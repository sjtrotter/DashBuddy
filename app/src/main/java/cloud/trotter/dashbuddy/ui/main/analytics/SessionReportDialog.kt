package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionReportRule
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionReportOperation

/** Driver statement about a dash report; all inputs and actions are hoisted to the screen. */
@Composable
internal fun SessionReportDialog(
    session: SessionRecord,
    amount: String,
    onAmountChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    onConfirm: (String, Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val value = amount.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it <= SessionReportRule.MAX_REPORTED }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_report_dialog_title, session.platform.displayName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = onAmountChange,
                    label = { Text(stringResource(R.string.session_report_dialog_amount)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    label = { Text(stringResource(R.string.session_report_dialog_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { onConfirm(SessionReportOperation.CLEAR, null) }) {
                    Text(stringResource(R.string.session_report_dialog_clear))
                }
                TextButton(
                    onClick = { onConfirm(SessionReportOperation.RESTORE_MACHINE, null) },
                    enabled = session.reportCorrectedAt != null,
                ) { Text(stringResource(R.string.session_report_dialog_restore)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(SessionReportOperation.SET, value) }, enabled = value != null) {
                Text(stringResource(R.string.session_report_dialog_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_detail_cancel_button)) }
        },
    )
}
