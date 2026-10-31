package cloud.trotter.log.gig.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * #1457 — the Screen events disclosure as text: the body, the Android 11 caveat when it applies,
 * then the requirement note. The Automation & consent dialog and its "What this does" section both
 * render it; the permission chain's step and the debug block lay out the same parts themselves.
 */
@Composable
fun EventReceiptDisclosureText(
    disclosure: EventReceiptDisclosure,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column {
        Text(
            text = disclosure.body,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        disclosure.android11Caveat?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = disclosure.requirementNote,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * #1457 — shown when the Screen events switch is turned on from undecided or declined. Allow and
 * Don't allow each call [onDecision] (the caller writes ALLOWED or DECLINED with its receipt);
 * Back and the scrim call [onDismiss], which records nothing. Stateless.
 */
@Composable
fun EventReceiptConsentDialog(
    disclosure: EventReceiptDisclosure,
    onDecision: (allow: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_receipt_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EventReceiptDisclosureText(disclosure)
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(true) }) { Text(stringResource(R.string.event_receipt_allow)) }
        },
        dismissButton = {
            TextButton(onClick = { onDecision(false) }) { Text(stringResource(R.string.event_receipt_deny)) }
        },
    )
}

/**
 * #1457 — a test build asks before Screen events goes off, because there turning it off also turns
 * screen reading off. [onConfirm] turns it off; Cancel, Back and the scrim call [onDismiss]. Stateless.
 */
@Composable
fun EventReceiptOffConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.event_receipt_off_confirm_title)) },
        text = { Text(stringResource(R.string.event_receipt_off_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.event_receipt_off_confirm_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.event_receipt_off_confirm_cancel)) }
        },
    )
}
