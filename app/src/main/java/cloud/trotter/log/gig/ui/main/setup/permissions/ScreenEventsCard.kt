package cloud.trotter.log.gig.ui.main.setup.permissions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.feature.settings.R as SettingsR
import cloud.trotter.log.gig.feature.settings.eventReceiptDisclosureParts

/**
 * #1151 (dev re-sequencing, 2026-09-30) — the permission chain's FIRST step, before the
 * accessibility grant: the event-receipt disclosure with Allow / Don't allow, of equal weight
 * (#1457). Either answer is a lasting decision written through `EventReceiptPreferences`; only
 * closing the sheet puts the step off. The copy is the shared [eventReceiptDisclosureParts], so this
 * step, the Automation & consent dialog and the debug block say the same thing. Stateless; the
 * decision goes up through [onDecision].
 */
@Composable
fun ScreenEventsCard(onDecision: (allow: Boolean) -> Unit) {
    val disclosure = eventReceiptDisclosureParts()
    PermissionStepColumn {
        PermissionStepHeader(
            stepNumber = PermissionStep.ScreenEvents.stepNumber,
            iconRes = null,
            titleRes = SettingsR.string.event_receipt_title,
        )
        Text(
            text = stringResource(R.string.perm_pitch_screen_events),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PermissionBodyText(disclosure.body)
        disclosure.android11Caveat?.let {
            Spacer(Modifier.height(8.dp))
            PermissionBodyText(it)
        }
        // RR1: debug requires, release offers — the variant's own note (a source-set override).
        PermissionWithoutIt(disclosure.requirementNote + " " + disclosure.changeLater)
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = { onDecision(false) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(SettingsR.string.event_receipt_deny)) }
            FilledTonalButton(
                onClick = { onDecision(true) },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(SettingsR.string.event_receipt_allow)) }
        }
        Spacer(Modifier.height(16.dp))
    }
}
