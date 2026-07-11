package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatShortDate

/**
 * The "(No session)" categorize flow (#660 piece 2) — a two-step dialog opened from the Money-tab
 * callout. Step 1 lists the period's orphan deliveries; tapping one opens step 2, the session picker
 * (ENDED dashes within ±48 h of the orphan's completion, same platform, nearest first). Confirming
 * writes a `DELIVERY_SESSION_ASSIGN` via the ViewModel; Room invalidation shrinks [orphans]
 * reactively (the assigned row leaves the bucket), so the list updates without a manual refresh.
 *
 * Kept OUT of `MoneyTab.kt` (Principle 3 — file-size budget). Pure data in / lambdas out (Principle 1):
 * [candidateSessionsFor] is a suspend fetch the picker runs in a [produceState]; [onAssign] appends the
 * correction event. No PII surface — store/merchant names are driver-owned; no customer hashes.
 */
@Composable
fun NoSessionAssignDialog(
    orphans: List<DeliveryRecord>,
    candidateSessionsFor: suspend (DeliveryRecord) -> List<SessionRecord>,
    onAssign: (targetEventSequenceId: Long, newSessionId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedOrphan by remember { mutableStateOf<DeliveryRecord?>(null) }
    val orphan = selectedOrphan

    if (orphan == null) {
        OrphanListDialog(
            orphans = orphans,
            onSelect = { selectedOrphan = it },
            onDismiss = onDismiss,
        )
    } else {
        SessionPickerDialog(
            orphan = orphan,
            candidateSessionsFor = candidateSessionsFor,
            onAssign = { sessionId ->
                onAssign(orphan.eventSequenceId, sessionId)
                // Return to the (reactively-shrinking) list so the driver can categorize more.
                selectedOrphan = null
            },
            onBack = { selectedOrphan = null },
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun OrphanListDialog(
    orphans: List<DeliveryRecord>,
    onSelect: (DeliveryRecord) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.no_session_assign_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.no_session_assign_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
                if (orphans.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_session_assign_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    orphans.forEach { o ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(o) }
                                .padding(vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = o.storeName ?: stringResource(R.string.no_session_assign_unknown_store),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.text,
                                )
                                Text(
                                    text = formatShortDate(o.completedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.text3,
                                )
                            }
                            Text(
                                text = o.realizedPay?.let { Formats.money(it) } ?: EMPTY_VALUE,
                                style = AppTheme.num.smNum,
                                color = c.text,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.no_session_assign_close)) }
        },
    )
}

@Composable
private fun SessionPickerDialog(
    orphan: DeliveryRecord,
    candidateSessionsFor: suspend (DeliveryRecord) -> List<SessionRecord>,
    onAssign: (newSessionId: String) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    // Re-fetched whenever the target orphan changes; null while the suspend read is in flight.
    val candidates by produceState<List<SessionRecord>?>(initialValue = null, orphan) {
        value = candidateSessionsFor(orphan)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.no_session_assign_pick_dash_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val list = candidates
                when {
                    list == null -> Text(
                        text = stringResource(R.string.no_session_assign_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    list.isEmpty() -> Text(
                        text = stringResource(R.string.no_session_assign_no_candidates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    else -> list.forEach { s ->
                        val deliveryWord = if (s.deliveries == 1) {
                            stringResource(R.string.time_tab_delivery_singular)
                        } else {
                            stringResource(R.string.time_tab_delivery_plural)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAssign(s.sessionId) }
                                .padding(vertical = 8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = formatShortDate(s.startedAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = c.text,
                                )
                                Text(
                                    // Platform label is registry-resolved (never a literal) — Principle 8.
                                    text = stringResource(
                                        R.string.no_session_assign_session_deliveries_format,
                                        s.platform.shortName.ifEmpty { s.platform.displayName },
                                        Formats.commaInt(s.deliveries),
                                        deliveryWord,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.text3,
                                )
                            }
                            Text(
                                text = s.reportedEarnings?.let { Formats.money(it) } ?: EMPTY_VALUE,
                                style = AppTheme.num.smNum,
                                color = c.text,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.no_session_assign_back)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.session_detail_cancel_button)) }
        },
    )
}
