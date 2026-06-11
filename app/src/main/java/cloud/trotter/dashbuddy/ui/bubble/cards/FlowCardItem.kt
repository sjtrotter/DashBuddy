package cloud.trotter.dashbuddy.ui.bubble.cards

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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import cloud.trotter.dashbuddy.core.designsystem.format.DashFormats
import cloud.trotter.dashbuddy.core.designsystem.theme.DashTheme
import cloud.trotter.dashbuddy.core.designsystem.time.formatCountdown
import cloud.trotter.dashbuddy.core.designsystem.time.formatDuration
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip
import cloud.trotter.dashbuddy.core.designsystem.component.DashChip
import cloud.trotter.dashbuddy.core.designsystem.component.DashGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.theme.DashColors
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.ui.formatters.displayLabel
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.state.customerDisplayName

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
            CardHeader(snapshot = snapshot, isActive = isActive, expanded = effectiveExpanded)
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
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PhaseChip(snapshot: FlowCardSnapshot, isActive: Boolean) {
    // Semantic brand tokens per DashColors' contract (#358) — the chip now
    // agrees with statusBadge instead of remapping phases onto M3 roles.
    val c = DashTheme.colors
    val (label, color, bg) = when (snapshot) {
        is FlowCardSnapshot.Awaiting -> Triple("AWAIT", c.neutral, c.neutralBg)
        is FlowCardSnapshot.Offer -> Triple("OFFER", c.stOffer, c.stOfferBg)
        is FlowCardSnapshot.Pickup -> Triple("PICKUP", c.stPickup, c.stPickupBg)
        is FlowCardSnapshot.Delivery -> Triple("DROPOFF", c.stPickup, c.stPickupBg)
        is FlowCardSnapshot.PostTask -> Triple("PAID", c.good, c.goodBg)
    }
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
    val pay = snap.payAmount?.let { " · ${DashFormats.money(it)}" } ?: ""
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
    val customer = customerDisplayName(snap.customerHash)
    val arrivedAt = snap.arrivedAt
    return when {
        isActive -> customer
        arrivedAt != null -> "$customer · delivered ${formatTime(arrivedAt)}"
        else -> customer
    }
}

private fun postTaskSummary(snap: FlowCardSnapshot.PostTask): String {
    val store = snap.storeName?.let { "$it · " } ?: ""
    return store + DashFormats.money(snap.totalPay)
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
        HeroBig(formatDuration(elapsed))
        Caption(
            if (isActive) "since last offer"
            else "before next offer"
        )
    }
}

/**
 * Offer body (redesign): a live expiry bar, a score ring beside the net $/hr
 * hero, a verdict banner (action + reason + quality), and badge pills. The
 * DoorDash expiry countdown also ticks in the header. (The Accept/Decline
 * action row + auto-action countdown land in Stage 2/3 of #110.)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OfferBody(snap: FlowCardSnapshot.Offer, isActive: Boolean) {
    val c = DashTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Live expiry progress bar (DoorDash's own offer countdown), active only.
        val expiresAt = snap.expiresAt
        val countdownSeconds = snap.countdownSeconds
        if (isActive && expiresAt != null && countdownSeconds != null && countdownSeconds > 0) {
            val now by rememberNow()
            val secsLeft = ((expiresAt - now) / 1000f).coerceAtLeast(0f)
            val frac = (secsLeft / countdownSeconds).coerceIn(0f, 1f)
            val barColor = offerExpiryColor(frac.toDouble(), c)
            Box(
                Modifier.fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(c.line),
            ) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(barColor))
            }
        }

        // Score ring + net $/hr hero.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            snap.evaluationScore?.let { score ->
                // Ring colors track the evaluator's REAL decision boundaries (#400).
                val sc = when {
                    score >= OfferEvaluator.ACCEPT_THRESHOLD -> c.good
                    score <= OfferEvaluator.DECLINE_THRESHOLD -> c.bad
                    else -> c.warn
                }
                DashGaugeRing(
                    progress = (score / 100.0).toFloat(),
                    value = score.toInt().toString(),
                    label = "Score",
                    color = sc,
                    diameter = 60.dp,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                val hourly = snap.dollarsPerHour
                when {
                    hourly != null -> Text("${DashFormats.money0(hourly)}/hr", style = DashTheme.num.heroNum, color = c.text, maxLines = 1)
                    snap.payAmount != null -> Text(DashFormats.money(snap.payAmount!!), style = DashTheme.num.heroNum, color = c.text, maxLines = 1)
                }
                val net = snap.netPayAmount ?: snap.payAmount
                val secondary = buildString {
                    if (net != null) append("Net ${DashFormats.money(net)}")
                    snap.distanceMiles?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${DashFormats.decimal(it)} mi")
                    }
                    snap.dollarsPerMile?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${DashFormats.money(it)}/mi")
                    }
                }
                if (secondary.isNotBlank()) {
                    Text(secondary, style = DashTheme.num.smNum, color = c.text2, maxLines = 1)
                }
            }
        }

        // Verdict banner — action word + reason + quality chip, tinted by the action.
        snap.evaluationAction?.let { action ->
            val vColor = when (action) {
                "ACCEPT" -> c.good
                "DECLINE" -> c.bad
                else -> c.warn
            }
            val vBg = when (action) {
                "ACCEPT" -> c.goodBg
                "DECLINE" -> c.badBg
                else -> c.warnBg
            }
            val vIcon = when (action) {
                "ACCEPT" -> Icons.Default.Check
                "DECLINE" -> Icons.Default.Close
                else -> Icons.Default.Info
            }
            Surface(shape = MaterialTheme.shapes.small, color = vBg) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(vIcon, contentDescription = null, tint = vColor, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            action.replace('_', ' '),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = vColor,
                        )
                    }
                    snap.qualityLevel?.let { DashChip(it.displayLabel(), color = c.text3, container = c.surface3) }
                }
            }
        }

        // Badge pills.
        if (snap.badges.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                snap.badges.forEach { b ->
                    val (label, col) = badgeMeta(b, c)
                    DashChip(label, color = col, container = c.surface3)
                }
            }
        }

        // Footer: stores · items.
        val storeText = snap.storeNames.joinToString(" & ").ifBlank { null }
        val footer = buildString {
            storeText?.let { append(it) }
            if (snap.itemCount > 1) {
                if (isNotEmpty()) append(" · ")
                append("${snap.itemCount} items")
            }
        }
        if (footer.isNotBlank()) Caption(footer)
    }
}

/** Live m:ss offer-expiry countdown for the active offer card header. */
@Composable
private fun OfferCountdownText(expiresAt: Long, countdownSeconds: Int) {
    val c = DashTheme.colors
    val now by rememberNow()
    val secsLeft = ((expiresAt - now) / 1000.0).coerceAtLeast(0.0)
    val frac = if (countdownSeconds > 0) (secsLeft / countdownSeconds).coerceIn(0.0, 1.0) else 0.0
    val col = offerExpiryColor(frac, c)
    Text(
        text = formatCountdown((secsLeft * 1000).toLong()),
        style = DashTheme.num.smNum,
        color = col,
        fontWeight = FontWeight.Bold,
    )
}

/** Maps a badge enum name to a short label + brand color for the pill row. */
private fun badgeMeta(name: String, c: DashColors): Pair<String, Color> = when (name) {
    "HIGH_PAYING" -> "High pay" to c.good
    "PRIORITY_ACCESS" -> "Priority" to c.stOffer
    "RED_CARD" -> "Red Card" to c.bad
    "ALCOHOL" -> "Alcohol" to c.warn
    "LARGE_ORDER" -> "Large order" to c.neutral
    "PIZZA_BAG" -> "Pizza bag" to c.neutral
    "ALL_ORDERS_SAME_STORE" -> "Same store" to c.neutral
    "BOTH_ORDERS_SAME_CUSTOMER" -> "Same customer" to c.neutral
    "ITEMS_CAN_BE_ADDED" -> "Add-ons OK" to c.neutral
    else -> name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } to c.neutral
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
        itemsShopped = snap.itemsShopped,
        itemsRemaining = snap.itemsRemaining,
        activity = snap.activity,
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
        primary = customerDisplayName(snap.customerHash),
        confirmedAt = null,
        itemsShopped = null,
        itemsRemaining = null,
        activity = null,
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
    itemsShopped: Int?,
    itemsRemaining: Int?,
    activity: String?,
    deadlineLabel: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val now = if (isActive) rememberNow().value else (phaseEndedAt ?: phaseStartedAt)

        if (isActive) {
            if (deadlineMillis != null) {
                val remaining = deadlineMillis - now
                HeroBig(text = formatCountdown(remaining), color = deadlineColor(remaining))
                // Caption is `${deadlineLabel} · by HH:MM` — the wall-clock anchor
                // lets the dasher cross-check the countdown against what DoorDash
                // itself is showing on screen (field log 2026-05-19 #1 / 2026-05-17 #2).
                Caption("$deadlineLabel · by ${formatTime(deadlineMillis)}")
            } else {
                // No deadline parsed — fall back to elapsed time
                HeroBig(formatDuration(now - phaseStartedAt))
                Caption(if (arrivedAt == null) "en route" else "at stop")
            }
        } else {
            // Frozen card. Three cases:
            //   (a) arrivedAt + deadlineMillis → delta vs deadline ("+Xm ahead" / "Xm late")
            //   (b) arrivedAt null but phaseEndedAt set → drop-off completed without
            //       an observed arrival sub-state (typical DoorDash no-contact flow).
            //       Show total phase duration with started/completed times in
            //       the tertiary row instead of the "—" placeholder.
            //   (c) nothing useful → placeholder
            val arrivalRemaining = if (deadlineMillis != null && arrivedAt != null)
                deadlineMillis - arrivedAt else null
            when {
                arrivalRemaining != null -> {
                    val deltaLabel = if (arrivalRemaining >= 0)
                        "+${formatCountdown(arrivalRemaining)} ahead"
                    else
                        "${formatCountdown(-arrivalRemaining)} late"
                    HeroBig(text = deltaLabel, color = deadlineColor(arrivalRemaining))
                    Caption("vs $deadlineLabel")
                }
                phaseEndedAt != null -> {
                    HeroBig(formatDuration(phaseEndedAt - phaseStartedAt))
                    Caption("duration")
                }
                else -> {
                    HeroBig("—")
                    Caption(deadlineLabel)
                }
            }
        }

        // Tertiary row — store/customer + lifecycle times + items
        val tertiary = buildString {
            append(primary)
            arrivedAt?.let {
                append(" · arrived ${formatTime(it)}")
            }
            confirmedAt?.let {
                append(" · picked up ${formatTime(it)}")
            }
            // When we don't have an arrival (no-contact-delivery case),
            // surface the started/completed wall-clock so the dasher can
            // read what happened — paralleling Pickup's "arrived · picked up".
            if (arrivedAt == null && phaseEndedAt != null && !isActive) {
                append(" · started ${formatTime(phaseStartedAt)}")
                append(" · completed ${formatTime(phaseEndedAt)}")
            }
            // Shop & Deliver: show progress + a live items/min pace (derived off
            // `now`, so it ticks while shopping and freezes with the card). The bare
            // "items left" count alone isn't useful; pace + shopped/total is.
            if (activity == PickupActivity.SHOPPING) {
                val shopped = itemsShopped ?: 0
                val total = shopped + (itemsRemaining ?: 0)
                if (total > 0) {
                    append(" · shop $shopped/$total")
                    val elapsedMs = now - (arrivedAt ?: phaseStartedAt)
                    if (elapsedMs > 0) {
                        val perMin = shopped / (elapsedMs / 60_000.0)
                        append(" · ${DashFormats.decimal(perMin)}/min")
                    }
                }
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
        HeroBig(DashFormats.money(snap.totalPay))
        Caption("delivery total")
        snap.parsedPay?.let { pay ->
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                pay.appPayComponents.forEach { item ->
                    BreakdownRow(item.type, DashFormats.money(item.amount))
                }
                pay.customerTips.forEach { tip ->
                    BreakdownRow("tip · ${tip.type}", DashFormats.money(tip.amount))
                }
            }
        }
        snap.sessionEarningsAtCompletion?.let {
            Caption("session ${DashFormats.money(it)}")
        }
    }
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/** Manual Accept / Decline buttons on the active offer card (#110 Stage 2b). */
@Composable
private fun OfferActionRow(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = DashTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onDecline,
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.5.dp, c.bad),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.bad),
        ) { Text("Decline", fontWeight = FontWeight.Bold) }
        Button(
            onClick = onAccept,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = c.good, contentColor = c.textInv),
        ) { Text("Accept", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun HeroBig(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(
        text = text,
        style = DashTheme.num.heroNum,
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
private fun OutcomeChip(outcome: AppEventType) {
    val c = DashTheme.colors
    val (text, color) = when (outcome) {
        AppEventType.OFFER_ACCEPTED -> "Accepted" to c.good
        AppEventType.OFFER_DECLINED -> "Declined" to c.bad
        AppEventType.OFFER_TIMEOUT -> "Timed out" to c.neutral
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
    val c = DashTheme.colors
    return when {
        remainingMs < 0 -> c.bad              // past — red
        mins < 5 -> c.bad                     // <5m — red
        mins < 10 -> c.warn                   // 5-10m — amber
        else -> c.good                        // >10m — green
    }
}

// ---------------------------------------------------------------------------
// Time helpers — the shared kit lives in :core:designsystem (#358)
// ---------------------------------------------------------------------------

/** THE offer-expiry color tiers (#406) — previously written twice in this file. */
private fun offerExpiryColor(frac: Double, c: DashColors): Color = when {
    frac > 0.45 -> c.good
    frac > 0.2 -> c.warn
    else -> c.bad
}

@Composable
private fun formatTime(millis: Long): String = rememberTimeFormatter().invoke(millis)
