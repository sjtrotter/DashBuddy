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
 * The wide-event-receipt consent (#1151) — one page of the front door: Allow / Don't allow write
 * through [onDecision]. "Not now" is the door's own button ([FrontDoorSheet]); it leaves the value
 * UNDECIDED and closes the door for this foreground. Stateless; [FrontDoorHost] decides when it shows.
 */
@Composable
fun EventReceiptConsentPage(
    onDecision: (allow: Boolean) -> Unit,
) {
    FrontDoorPage(
        title = stringResource(SettingsR.string.event_receipt_title),
        body = eventReceiptDisclosure(),
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
