package cloud.trotter.dashbuddy.ui.main.setup.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.feature.settings.R as SettingsR
import cloud.trotter.dashbuddy.feature.settings.eventReceiptDisclosure
import cloud.trotter.dashbuddy.feature.settings.eventReceiptRequirementNote
import cloud.trotter.dashbuddy.feature.settings.eventReceiptSettingsPath

/**
 * #1151 (dev re-sequencing, 2026-09-30) — the permission chain's FIRST step, before the
 * accessibility grant: the event-receipt disclosure with Allow / Don't allow. No "Not now": this
 * step gates the next one (the sheet itself behaves like every permission card — shown again on the
 * next foreground while the step is due). Stateless; the decision goes up through [onDecision].
 */
@Composable
fun ScreenEventsCard(onDecision: (allow: Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(SettingsR.string.event_receipt_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = eventReceiptDisclosure(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        // RR1: debug requires, release offers — the variant's own note (a source-set override).
        Text(
            text = eventReceiptRequirementNote(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * #1151 — on a DEBUG build that declined, this stands IN PLACE of the accessibility step: the grant
 * is never offered while declined. (The shell-level `DebugEventReceiptShell` normally covers the
 * whole app first; this is the chain's own answer.)
 */
@Composable
fun ScreenEventsDeclinedCard(
    onOpenConsentSettings: () -> Unit,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.debug_event_receipt_block_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.debug_event_receipt_block_body, eventReceiptSettingsPath()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenConsentSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.debug_event_receipt_block_open_settings))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.debug_event_receipt_block_exit))
        }
        Spacer(Modifier.height(16.dp))
    }
}
