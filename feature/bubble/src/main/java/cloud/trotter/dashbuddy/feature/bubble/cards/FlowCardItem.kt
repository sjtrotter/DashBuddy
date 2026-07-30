package cloud.trotter.dashbuddy.feature.bubble.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.flowPhase
import cloud.trotter.dashbuddy.domain.state.presentation
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.formatters.color
import cloud.trotter.dashbuddy.feature.bubble.formatters.phaseBg

/**
 * A single flow-phase card in the bubble HUD stack (#257).
 *
 * Visual structure:
 * - Header: phase chip + summary line (always visible) + chevron when frozen.
 * - Body: minimal — one hero value, one secondary line, one tertiary line.
 *   Active cards (`isActive == true`) are always expanded and use the 1Hz
 *   `rememberNow()` ticker for countdowns. Frozen cards default collapsed
 *   and expand on tap, rendering statically from the snapshot.
 *
 * The per-card-kind renderers live in sibling files in this package — `FlowCardOffer.kt`
 * ([OfferBody]), `FlowCardTask.kt` ([AwaitingBody]/[PickupBody]/[DeliveryBody]), and
 * `FlowCardPostTask.kt` ([PostTaskBody]) — plus the shared primitives kept here ([HeroBig],
 * [Caption]). #943 split this file at those seams; pure move, no behavior change.
 */
@Composable
fun FlowCardItem(
    snapshot: FlowCardSnapshot,
    isActive: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The owning platform's short name (#867), rendered as a leading chip. Null ⇒ no chip: the
     * caller passes a label only while ≥2 dashes are live, so a single-platform HUD is unchanged
     * and a two-platform HUD can never show an unattributed card.
     */
    platformLabel: String? = null,
    onAccept: () -> Unit = {},
    onDecline: () -> Unit = {},
) {
    val effectiveExpanded = isActive || expanded
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 3.dp else 1.dp,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (!isActive) it.clickable { onToggleExpand() } else it }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(if (effectiveExpanded) 10.dp else 4.dp),
        ) {
            CardHeader(
                snapshot = snapshot,
                isActive = isActive,
                expanded = effectiveExpanded,
                platformLabel = platformLabel,
            )
            if (effectiveExpanded) {
                CardBody(snapshot = snapshot, isActive = isActive)
                if (snapshot is FlowCardSnapshot.Offer && isActive && snapshot.outcome == null) {
                    OfferActionRow(onAccept = onAccept, onDecline = onDecline)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header (always visible)
// ---------------------------------------------------------------------------

@Composable
private fun CardHeader(
    snapshot: FlowCardSnapshot,
    isActive: Boolean,
    expanded: Boolean,
    platformLabel: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // #867: multi-app attribution leads the row — whose dash is this card? Shown only when the
        // caller resolved a label (≥2 live sessions).
        platformLabel?.let { PlatformChip(it) }
        PhaseChip(snapshot, isActive)
        Text(
            text = cardSummary(snapshot, isActive),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        // Trailing status chip — shown in the header so collapsed cards
        // surface the outcome at a glance.
        if (snapshot is FlowCardSnapshot.Offer) {
            val outcome = snapshot.outcome
            val expiresAt = snapshot.expiresAt
            val countdownSeconds = snapshot.countdownSeconds
            if (outcome != null) {
                OutcomeChip(outcome)
            } else if (isActive && expiresAt != null && countdownSeconds != null) {
                OfferCountdownText(expiresAt, countdownSeconds)
            }
        }
        if (!isActive) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) stringResource(R.string.flow_card_content_desc_collapse)
                else stringResource(R.string.flow_card_content_desc_expand),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The owning platform's badge (#867). Deliberately quiet — it's an attribution marker, not a
 * status: the phase chip beside it keeps the loud color.
 */
@Composable
private fun PlatformChip(label: String) {
    val c = AppTheme.colors
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = c.neutralBg,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = c.neutral,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun PhaseChip(snapshot: FlowCardSnapshot, isActive: Boolean) {
    // Short-form label + color from the phase-presentation SSOT (#audit-12) —
    // the same row statusBadge() reads, so the two can no longer drift. The chip
    // uses the SHORT label/color; the status badge uses the long pair on the same
    // entry. Background is the family's *Bg variant.
    val c = AppTheme.colors
    val p = snapshot.flowPhase().presentation
    val label = p.shortLabel
    val color = p.shortColor.color(c)
    val bg = p.shortColor.phaseBg(c)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = if (isActive) bg else bg.copy(alpha = bg.alpha * 0.6f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Summary (one-line, always visible in header) — dispatches to the per-kind
// renderers in the sibling FlowCard*.kt files.
// ---------------------------------------------------------------------------

@Composable
private fun cardSummary(snapshot: FlowCardSnapshot, isActive: Boolean): String = when (snapshot) {
    is FlowCardSnapshot.Awaiting -> awaitingSummary(snapshot, isActive)
    is FlowCardSnapshot.Offer -> offerSummary(snapshot)
    is FlowCardSnapshot.Pickup -> pickupSummary(snapshot, isActive)
    is FlowCardSnapshot.Delivery -> deliverySummary(snapshot, isActive)
    is FlowCardSnapshot.PostTask -> postTaskSummary(snapshot)
}

// ---------------------------------------------------------------------------
// Body (expanded content) — dispatches to the per-kind renderers in the
// sibling FlowCard*.kt files.
// ---------------------------------------------------------------------------

@Composable
private fun CardBody(snapshot: FlowCardSnapshot, isActive: Boolean) {
    when (snapshot) {
        is FlowCardSnapshot.Awaiting -> AwaitingBody(snapshot, isActive)
        is FlowCardSnapshot.Offer -> OfferBody(snapshot, isActive)
        is FlowCardSnapshot.Pickup -> PickupBody(snapshot, isActive)
        is FlowCardSnapshot.Delivery -> DeliveryBody(snapshot, isActive)
        is FlowCardSnapshot.PostTask -> PostTaskBody(snapshot)
    }
}

// ---------------------------------------------------------------------------
// Primitives shared across the per-kind renderers.
// ---------------------------------------------------------------------------

@Composable
internal fun HeroBig(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        style = AppTheme.num.heroNum,
        color = color,
        maxLines = 1,
    )
}

@Composable
internal fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun OutcomeChip(outcome: AppEventType) {
    val c = AppTheme.colors
    val (text, color) = when (outcome) {
        AppEventType.OFFER_ACCEPTED -> stringResource(R.string.flow_card_outcome_accepted) to c.good
        AppEventType.OFFER_DECLINED -> stringResource(R.string.flow_card_outcome_declined) to c.bad
        AppEventType.OFFER_TIMEOUT -> stringResource(R.string.flow_card_outcome_timed_out) to c.neutral
        else -> outcome.name to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
