package cloud.trotter.dashbuddy.ui.bubble.cards

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import kotlinx.coroutines.delay
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * A single flow-phase card in the bubble HUD stack (#257).
 *
 * Visual structure:
 * - Header: phase chip + summary line (always visible) + chevron when frozen.
 * - Body: minimal — one hero value, one secondary line, one tertiary line.
 *   Active cards (`isActive == true`) are always expanded and use the 1Hz
 *   `rememberNow()` ticker for countdowns. Frozen cards default collapsed
 *   and expand on tap, rendering statically from the snapshot.
 */
@Composable
fun FlowCardItem(
    snapshot: FlowCardSnapshot,
    isActive: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
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
            CardHeader(snapshot = snapshot, isActive = isActive, expanded = effectiveExpanded)
            if (effectiveExpanded) {
                CardBody(snapshot = snapshot, isActive = isActive)
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
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
            snapshot.outcome?.let { OutcomeChip(it) }
        }
        if (!isActive) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PhaseChip(snapshot: FlowCardSnapshot, isActive: Boolean) {
    val (label, color) = when (snapshot) {
        is FlowCardSnapshot.Awaiting -> "AWAIT" to MaterialTheme.colorScheme.tertiary
        is FlowCardSnapshot.Offer -> "OFFER" to MaterialTheme.colorScheme.primary
        is FlowCardSnapshot.Pickup -> "PICKUP" to MaterialTheme.colorScheme.secondary
        is FlowCardSnapshot.Delivery -> "DROP" to MaterialTheme.colorScheme.secondary
        is FlowCardSnapshot.PostTask -> "PAID" to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = if (isActive) 0.22f else 0.14f),
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
// Summary (one-line, always visible in header)
// ---------------------------------------------------------------------------

@Composable
private fun cardSummary(snapshot: FlowCardSnapshot, isActive: Boolean): String = when (snapshot) {
    is FlowCardSnapshot.Awaiting -> awaitingSummary(snapshot, isActive)
    is FlowCardSnapshot.Offer -> offerSummary(snapshot)
    is FlowCardSnapshot.Pickup -> pickupSummary(snapshot, isActive)
    is FlowCardSnapshot.Delivery -> deliverySummary(snapshot, isActive)
    is FlowCardSnapshot.PostTask -> postTaskSummary(snapshot)
}

@Composable
private fun awaitingSummary(snap: FlowCardSnapshot.Awaiting, isActive: Boolean): String {
    val now = if (isActive) rememberNow().value else (snap.phaseEndedAt ?: snap.phaseStartedAt)
    val elapsed = now - snap.phaseStartedAt
    return if (isActive) "Waiting · ${formatDuration(elapsed)}" else "Waited ${formatDuration(elapsed)}"
}

private fun offerSummary(snap: FlowCardSnapshot.Offer): String {
    val store = snap.storeNames.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Offer"
    val pay = snap.payAmount?.let { " · $%.2f".format(it) } ?: ""
    // Outcome is rendered as a trailing chip in the header — see
    // CardHeader — so we omit it from the summary text.
    return "$store$pay"
}

@Composable
private fun pickupSummary(snap: FlowCardSnapshot.Pickup, isActive: Boolean): String {
    val store = snap.storeName
    val arrivedAt = snap.arrivedAt
    return when {
        isActive -> store
        arrivedAt != null -> "$store · arrived ${formatTime(arrivedAt)}"
        else -> store
    }
}

@Composable
private fun deliverySummary(snap: FlowCardSnapshot.Delivery, isActive: Boolean): String {
    val customer = snap.customerHash?.take(6) ?: "Customer"
    val arrivedAt = snap.arrivedAt
    return when {
        isActive -> customer
        arrivedAt != null -> "$customer · delivered ${formatTime(arrivedAt)}"
        else -> customer
    }
}

private fun postTaskSummary(snap: FlowCardSnapshot.PostTask): String {
    val store = snap.storeName?.let { "$it · " } ?: ""
    return "$store$%.2f".format(snap.totalPay)
}

// ---------------------------------------------------------------------------
// Body (expanded content) — minimal density per phase
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

@Composable
private fun AwaitingBody(snap: FlowCardSnapshot.Awaiting, isActive: Boolean) {
    val now = if (isActive) rememberNow().value else (snap.phaseEndedAt ?: snap.phaseStartedAt)
    val elapsed = now - snap.phaseStartedAt
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeroBig(if (isActive) formatDuration(elapsed) else formatDuration(elapsed))
        Caption(
            if (isActive) "since last offer"
            else "before next offer"
        )
    }
}

/**
 * Offer body: hero is **Net $/hr** (the "is this worth my time" number).
 * Subtitle = net pay · miles. Third row = store · score chip.
 */
@Composable
private fun OfferBody(snap: FlowCardSnapshot.Offer, isActive: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val hourly = snap.dollarsPerHour
        if (hourly != null) {
            HeroBig("$%.2f/hr".format(hourly))
        } else if (snap.payAmount != null) {
            HeroBig("$%.2f".format(snap.payAmount))
        }
        val net = snap.netPayAmount ?: snap.payAmount
        val miles = snap.distanceMiles
        val secondary = buildString {
            if (net != null) append("Net $%.2f".format(net))
            if (miles != null) {
                if (isNotEmpty()) append(" · ")
                append("%.1f mi".format(miles))
            }
            snap.dollarsPerMile?.let {
                if (isNotEmpty()) append(" · ")
                append("$%.2f/mi".format(it))
            }
        }
        if (secondary.isNotBlank()) Caption(secondary)
        val storeText = snap.storeNames.joinToString(" & ").ifBlank { null }
        val tertiary = buildString {
            storeText?.let { append(it) }
            if (snap.itemCount > 1) {
                if (isNotEmpty()) append(" · ")
                append("${snap.itemCount} items")
            }
        }
        if (tertiary.isNotBlank()) Caption(tertiary)

        // Score chip — outcome chip lives in the header so collapsed
        // cards surface accept/decline at a glance.
        val score = snap.evaluationScore
        val action = snap.evaluationAction
        score?.let {
            Row(modifier = Modifier.padding(top = 4.dp)) {
                ScoreChip(it.toInt(), action)
            }
        }
    }
}

/**
 * Pickup body: hero is the **countdown to pickup-by deadline**, colored
 * green/amber/red. When no deadline is parsed, falls back to elapsed time.
 * Frozen cards show a "+Xm ahead" / "Xm late" delta vs the deadline plus
 * arrival info.
 */
@Composable
private fun PickupBody(snap: FlowCardSnapshot.Pickup, isActive: Boolean) {
    DeadlineBody(
        phaseStartedAt = snap.phaseStartedAt,
        arrivedAt = snap.arrivedAt,
        deadlineMillis = snap.deadlineMillis,
        phaseEndedAt = snap.phaseEndedAt,
        isActive = isActive,
        primary = snap.storeName,
        confirmedAt = snap.confirmedAt,
        itemCount = snap.itemCount,
        deadlineLabel = "till pickup-by",
    )
}

/**
 * Delivery body: same shape as Pickup but with deliver-by deadline and
 * customer hash instead of store name.
 */
@Composable
private fun DeliveryBody(snap: FlowCardSnapshot.Delivery, isActive: Boolean) {
    DeadlineBody(
        phaseStartedAt = snap.phaseStartedAt,
        arrivedAt = snap.arrivedAt,
        deadlineMillis = snap.deadlineMillis,
        phaseEndedAt = snap.phaseEndedAt,
        isActive = isActive,
        primary = snap.customerHash?.take(6) ?: "Customer",
        confirmedAt = null,
        itemCount = null,
        deadlineLabel = "till deliver-by",
    )
}

/**
 * Shared body for Pickup + Delivery — both have a deadline + arrival
 * lifecycle. Hero is countdown-to-deadline while active; "+Xm ahead" /
 * "Xm late" once frozen.
 */
@Composable
private fun DeadlineBody(
    phaseStartedAt: Long,
    arrivedAt: Long?,
    deadlineMillis: Long?,
    phaseEndedAt: Long?,
    isActive: Boolean,
    primary: String,
    confirmedAt: Long?,
    itemCount: Int?,
    deadlineLabel: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val now = if (isActive) rememberNow().value else (phaseEndedAt ?: phaseStartedAt)

        if (deadlineMillis != null) {
            val remaining = deadlineMillis - now
            val color = deadlineColor(remaining)
            if (isActive) {
                HeroBig(text = formatCountdown(remaining), color = color)
                Caption(deadlineLabel)
            } else {
                val arrivalRemaining = arrivedAt?.let { deadlineMillis - it }
                if (arrivalRemaining != null) {
                    val deltaLabel = if (arrivalRemaining >= 0)
                        "+${formatCountdown(arrivalRemaining)} ahead"
                    else
                        "${formatCountdown(-arrivalRemaining)} late"
                    HeroBig(text = deltaLabel, color = deadlineColor(arrivalRemaining))
                    Caption("vs $deadlineLabel")
                } else {
                    HeroBig("—")
                    Caption(deadlineLabel)
                }
            }
        } else if (isActive) {
            // No deadline parsed — fall back to elapsed time
            HeroBig(formatDuration(now - phaseStartedAt))
            Caption(if (arrivedAt == null) "en route" else "at stop")
        }

        // Tertiary row — store/customer + arrival time + items
        val tertiary = buildString {
            append(primary)
            arrivedAt?.let {
                append(" · arrived ${formatTime(it)}")
            }
            confirmedAt?.let {
                append(" · picked up ${formatTime(it)}")
            }
            itemCount?.let {
                if (it > 0) append(" · ${it}i")
            }
        }
        Caption(tertiary)
    }
}

/**
 * PostTask body: receipt-style. Hero is total pay. Below: per-item
 * breakdown (base + tip) shown compactly.
 */
@Composable
private fun PostTaskBody(snap: FlowCardSnapshot.PostTask) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeroBig("$%.2f".format(snap.totalPay))
        Caption("delivery total")
        snap.parsedPay?.let { pay ->
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                pay.appPayComponents.forEach { item ->
                    BreakdownRow(item.type, "$%.2f".format(item.amount))
                }
                pay.customerTips.forEach { tip ->
                    BreakdownRow("tip · ${tip.type}", "$%.2f".format(tip.amount))
                }
            }
        }
        snap.sessionEarningsAtCompletion?.let {
            Caption("session $%.2f".format(it))
        }
    }
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

@Composable
private fun HeroBig(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        color = color,
        maxLines = 1,
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun BreakdownRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ScoreChip(score: Int, action: String?) {
    val color = when {
        score >= 70 -> Color(0xFF2E7D32)
        score <= 30 -> Color(0xFFC62828)
        else -> Color(0xFFF9A825)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.16f),
    ) {
        Text(
            text = if (action != null) "$score · $action" else score.toString(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun OutcomeChip(outcome: AppEventType) {
    val (text, color) = when (outcome) {
        AppEventType.OFFER_ACCEPTED -> "Accepted" to Color(0xFF2E7D32)
        AppEventType.OFFER_DECLINED -> "Declined" to Color(0xFFC62828)
        AppEventType.OFFER_TIMEOUT -> "Timed out" to Color(0xFF6D6D6D)
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

@Composable
private fun deadlineColor(remainingMs: Long): Color {
    val mins = remainingMs / 60_000L
    return when {
        remainingMs < 0 -> Color(0xFFC62828)              // past — red
        mins < 5 -> Color(0xFFC62828)                     // <5m — red
        mins < 10 -> Color(0xFFF9A825)                    // 5-10m — amber
        else -> Color(0xFF2E7D32)                          // >10m — green
    }
}

// ---------------------------------------------------------------------------
// Time helpers
// ---------------------------------------------------------------------------

/** 1Hz tick; scoped to this composable. See CLAUDE.md ▸ Reactive UI Principles. */
@Composable
internal fun rememberNow(tickMs: Long = 1000L): State<Long> =
    produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(tickMs)
            value = System.currentTimeMillis()
        }
    }

@Composable
internal fun rememberFormattedTime(): (Long) -> String {
    val ctx = LocalContext.current
    val use24 = DateFormat.is24HourFormat(ctx)
    val pattern = if (use24) "HH:mm" else "h:mm a"
    return { millis -> DateFormat.format(pattern, Date(millis)).toString() }
}

@Composable
private fun formatTime(millis: Long): String = rememberFormattedTime().invoke(millis)

internal fun formatDuration(millis: Long): String {
    val safe = millis.coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(safe)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return when {
        hours > 0 -> "%dh %dm".format(hours, minutes)
        minutes > 0 -> "%dm %ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}

/** "m:ss" style countdown for the deadline hero. Handles negatives by
 *  returning the absolute magnitude — callers add the "ahead/late" label. */
internal fun formatCountdown(millis: Long): String {
    val safe = kotlin.math.abs(millis)
    val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(safe)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return "%d:%02d".format(totalMinutes, seconds)
}
