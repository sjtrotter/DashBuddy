package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.feature.settings.R as SettingsR
import cloud.trotter.dashbuddy.feature.settings.eventReceiptDisclosure

/**
 * The wide-event-receipt consent prompt (#1151) on the shared [FrontDoorSheet]: Allow / Don't allow
 * write through [onDecision]; "Not now" (and scrim/back) call [onDefer] — the value stays UNDECIDED
 * and the host re-offers it on the next real return to the foreground. Stateless; whether it shows
 * is the [FrontDoorHost]'s call.
 */
@Composable
fun EventReceiptConsentSheet(
    onDecision: (allow: Boolean) -> Unit,
    onDefer: () -> Unit,
) {
    FrontDoorSheet(
        title = stringResource(SettingsR.string.event_receipt_title),
        body = eventReceiptDisclosure(),
        notNowLabel = stringResource(SettingsR.string.event_receipt_not_now),
        onDefer = onDefer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onDecision(false) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(SettingsR.string.event_receipt_deny)) }
            Button(
                onClick = { onDecision(true) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(SettingsR.string.event_receipt_allow)) }
        }
    }
}
