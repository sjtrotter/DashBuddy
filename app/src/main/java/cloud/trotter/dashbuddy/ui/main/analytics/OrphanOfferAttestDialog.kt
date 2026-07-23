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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferCandidate
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatClockTime
import cloud.trotter.dashbuddy.domain.format.formatShortDate

/**
 * The orphan-offer attestation flow (#810 B2 Tier 2) — a one-step dialog opened from the Money-tab
 * callout. Lists each still-open `JOB_ACCEPT_MISMATCH` group (an accepted-offer-vs-delivery mismatch
 * the projector's Tier-1 store-evidence join could not disambiguate — a same-store tie), and under each,
 * the job's accepted offers with store / pay / decision time (driver-recognizable facts). Tapping an
 * unresolved offer attests it as the invisibly-unassigned one; tapping an already-resolved offer undoes
 * it. Confirming writes an `OFFER_OUTCOME_CORRECTION` via the ViewModel; Room invalidation shrinks
 * [groups] reactively (a resolved orphan leaves the open set), so the list updates without a refresh.
 *
 * Kept OUT of `MoneyTab.kt` (Principle 3 — file-size budget). Pure data in / lambdas out (Principle 1):
 * [onResolve] appends the correction event. No PII surface — store names are merchant data; pay/time are
 * decision facts; no customer hashes.
 */
@Composable
fun OrphanOfferAttestDialog(
    groups: List<OrphanOfferGroup>,
    onResolve: (offerEventSequenceId: Long, attested: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AppTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.orphan_offers_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.orphan_offers_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
                if (groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.orphan_offers_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    groups.forEach { group ->
                        Text(
                            text = stringResource(
                                R.string.orphan_offers_group_header,
                                Formats.commaInt(group.offers.size),
                                Formats.commaInt(group.orphansOwed),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = c.text2,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        group.offers.forEach { offer ->
                            OfferRow(offer = offer, onResolve = onResolve)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.orphan_offers_close)) }
        },
    )
}

@Composable
private fun OfferRow(
    offer: OrphanOfferCandidate,
    onResolve: (offerEventSequenceId: Long, attested: Boolean) -> Unit,
) {
    val c = AppTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // Tap toggles resolution: an unresolved offer becomes attested; a resolved one undoes.
            .clickable { onResolve(offer.offerEventSequenceId, !offer.resolved) }
            .padding(vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = offer.storeName ?: stringResource(R.string.orphan_offers_unknown_store),
                style = MaterialTheme.typography.bodyMedium,
                color = if (offer.resolved) c.text3 else c.text,
            )
            Text(
                text = if (offer.resolved) {
                    stringResource(R.string.orphan_offers_resolved_caption)
                } else {
                    // Date AND time-of-day so two same-store offers stay distinguishable (the #660 pattern).
                    "${formatShortDate(offer.decidedAt)} · ${formatClockTime(offer.decidedAt)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        Text(
            text = offer.payAmount?.let { Formats.money(it) } ?: EMPTY_VALUE,
            style = AppTheme.num.smNum,
            color = if (offer.resolved) c.text3 else c.text,
        )
    }
}
