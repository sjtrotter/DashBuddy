package cloud.trotter.log.gig.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.analytics.SessionMilesRule
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.format.Formats
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToLong

/** The field's prefill: up to two decimals, machine-readable (never grouped or localized), blank when unknown. */
internal fun milesFieldText(miles: Double?): String =
    miles?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() }.orEmpty()

/**
 * #1535: the driver's statement of a shift's miles; every input and action is hoisted to the screen. Save is off for
 * an invalid or unchanged value; a value below the deliveries' own miles ([deliveryMiles]) warns and still saves.
 */
@Composable
internal fun SessionMilesDialog(
    session: SessionRecord,
    deliveryMiles: Double,
    miles: String,
    onMilesChange: (String) -> Unit,
    onSave: (Double) -> Unit,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    val value = miles.trim().toDoubleOrNull()?.takeIf { SessionMilesRule.isValidSet(it) }
    val invalid = miles.isNotBlank() && value == null
    // A save that changes nothing must not append a duplicate correction event (the #1134 F7 rule).
    val unchanged = value != null && session.miles != null &&
        (value * 100.0).roundToLong() == (session.miles!! * 100.0).roundToLong()
    val belowDeliveries = value != null && value < deliveryMiles - MILES_EPSILON
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_miles_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = miles,
                    onValueChange = onMilesChange,
                    label = { Text(stringResource(R.string.session_miles_dialog_field)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = invalid,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalid) {
                    Text(stringResource(R.string.session_miles_dialog_invalid), style = MaterialTheme.typography.bodySmall, color = c.bad)
                } else if (belowDeliveries) {
                    Text(stringResource(R.string.session_miles_dialog_below_deliveries), style = MaterialTheme.typography.bodySmall, color = c.warn)
                }
                if (session.milesOverride != null) {
                    TextButton(onClick = onRestore) {
                        Text(
                            session.machineMiles?.let { stringResource(R.string.session_miles_dialog_restore_format, Formats.decimal(it)) }
                                ?: stringResource(R.string.session_miles_dialog_restore),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null && !unchanged) {
                Text(stringResource(R.string.session_miles_dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_detail_cancel_button)) }
        },
    )
}

/** Half a hundredth of a mile: the dialog compares at the precision a driver can type. */
private const val MILES_EPSILON = 0.005
