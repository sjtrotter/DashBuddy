package cloud.trotter.dashbuddy.ui.main.setup.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cloud.trotter.dashbuddy.feature.settings.R as SettingsR
import cloud.trotter.dashbuddy.feature.settings.eventReceiptDisclosure

/**
 * The wide-event-receipt consent prompt (#1151) — the `ConsentPromptSheet` rhythm for a FEATURE
 * consent: a modal at the app's front door, shown after the permission chain while the decision is
 * undecided. Allow / Don't allow write through [onDecision]; "Not now" (and scrim/back) defer for
 * this foreground only — the value stays UNDECIDED and the sheet returns on the next ON_RESUME.
 *
 * Stateless over [showPrompt] (hoisted from [EventReceiptConsentViewModel]); the copy is the ONE
 * `event_receipt_*` set in `:feature:settings`, shared with the Settings switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventReceiptConsentSheet(
    showPrompt: Boolean,
    onDecision: (allow: Boolean) -> Unit,
) {
    // rememberSaveable: a rotation within the same foreground must not re-show a deferred sheet.
    var deferred by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { deferred = false }

    if (!showPrompt || deferred) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ModalBottomSheet(
        onDismissRequest = { deferred = true },
        sheetState = sheetState,
        modifier = Modifier.padding(bottom = bottomPadding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(SettingsR.string.event_receipt_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = eventReceiptDisclosure(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
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
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { deferred = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(SettingsR.string.event_receipt_not_now)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}
