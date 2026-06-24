package cloud.trotter.dashbuddy.ui.bubble.cards

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.painterResource
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.formatCountdown
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.ui.draw.clip
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.cards.TaskEconomics
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.flowPhase
import cloud.trotter.dashbuddy.domain.state.presentation
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.ui.formatters.color
import cloud.trotter.dashbuddy.ui.formatters.displayLabel
import cloud.trotter.dashbuddy.ui.formatters.offerBadgeIcon
import cloud.trotter.dashbuddy.ui.formatters.phaseBg
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluator
import cloud.trotter.dashbuddy.domain.state.customerLabel

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
    val pay = snap.payAmount?.let { " · ${Formats.money(it)}" } ?: ""
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
    val customer = customerLabel(snap.storeName)
    val arrivedAt = snap.arrivedAt
    return when {
        isActive -> customer
        arrivedAt != null -> "$customer · delivered ${formatTime(arrivedAt)}"
        else -> customer
    }
}

private fun postTaskSummary(snap: FlowCardSnapshot.PostTask): String {
    val store = snap.storeName?.let { "$it · " } ?: ""
    return store + Formats.money(snap.totalPay)
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
    val c = AppTheme.colors
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
                AppGaugeRing(
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
                    hourly != null -> Text("${Formats.money0(hourly)}/hr", style = AppTheme.num.heroNum, color = c.text, maxLines = 1)
                    snap.payAmount != null -> Text(Formats.money(snap.payAmount!!), style = AppTheme.num.heroNum, color = c.text, maxLines = 1)
                }
                val net = snap.netPayAmount ?: snap.payAmount
                val secondary = buildString {
                    if (net != null) append("Net ${Formats.money(net)}")
                    snap.distanceMiles?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${Formats.decimal(it)} mi")
                    }
                    snap.dollarsPerMile?.let {
                        if (isNotEmpty()) append(" · ")
                        append("${Formats.money(it)}/mi")
                    }
                }
                if (secondary.isNotBlank()) {
                    Text(secondary, style = AppTheme.num.smNum, color = c.text2, maxLines = 1)
                }
            }
            // (#461: the item count now rides the [cart N] shop badge below, not the hero tier.)
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
                    snap.qualityLevel?.let { AppChip(it.displayLabel(), color = c.text3, container = c.surface3) }
                }
            }
        }

        // Badge row (#461): icon badges tinted by their brand color; the SHOP badge is co-icon-text
        // [🛒 N] — the shopping-chat cart + the item count. Badges without a drawable fall back to a
        // text pill so nothing is dropped.
        if (snap.badges.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                snap.badges.forEach { b ->
                    val (label, col) = badgeMeta(b, c)
                    when {
                        b == "SHOP" -> BadgeIcon(
                            iconRes = R.drawable.ic_chat_shopping_cart,
                            label = snap.itemCount.takeIf { it > 0 }?.toString(),
                            color = col,
                        )
                        else -> badgeIcon(b)
                            ?.let { BadgeIcon(iconRes = it, label = null, color = col) }
                            ?: AppChip(label, color = col, container = c.surface3)
                    }
                }
            }
        }

        // Footer: stores. (Item count moved up to the hero tier, #461.)
        val storeText = snap.storeNames.joinToString(" & ").ifBlank { null }
        if (storeText != null) Caption(storeText)
    }
}

/** Live m:ss offer-expiry countdown for the active offer card header. */
@Composable
private fun OfferCountdownText(expiresAt: Long, countdownSeconds: Int) {
    val c = AppTheme.colors
    val now by rememberNow()
    val secsLeft = ((expiresAt - now) / 1000.0).coerceAtLeast(0.0)
    val frac = if (countdownSeconds > 0) (secsLeft / countdownSeconds).coerceIn(0.0, 1.0) else 0.0
    val col = offerExpiryColor(frac, c)
    Text(
        text = formatCountdown((secsLeft * 1000).toLong()),
        style = AppTheme.num.smNum,
        color = col,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Badge enum name → `ic_badge_*` drawable (#461); null means "no icon, render the text pill". The
 * SHOP badge is handled by the caller (the shopping-chat cart + the item count, [🛒 N]).
 * Delegates to the [offerBadgeIcon] SSOT (#578) so the bubble card and the offer notification share
 * one map and can't drift.
 */
@DrawableRes
private fun badgeIcon(name: String): Int? = offerBadgeIcon(name)

/**
 * Icon badge in the [AppChip] pill family (#461): a brand-tinted icon, optionally with a trailing
 * label — the SHOP badge rides its item count here ([🛒 N]). Mirrors AppChip's chip tokens.
 */
@Composable
private fun BadgeIcon(
    @DrawableRes iconRes: Int,
    label: String?,
    color: Color,
    container: Color = AppTheme.colors.surface3,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        if (label != null) Text(label, style = AppTheme.num.chip, color = color)
    }
}

/** Maps a badge enum name to a short label + brand color for the pill row. */
private fun badgeMeta(name: String, c: AppColors): Pair<String, Color> = when (name) {
    "SHOP" -> "Shop & Deliver" to c.stPickup
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

/** Pickup body — the #324/#460 task-card vocabulary (co-hero pair). */
@Composable
private fun PickupBody(snap: FlowCardSnapshot.Pickup, isActive: Boolean) {
    TaskBody(
        phaseStartedAt = snap.phaseStartedAt,
        arrivedAt = snap.arrivedAt,
        deadlineMillis = snap.deadlineMillis,
        phaseEndedAt = snap.phaseEndedAt,
        isActive = isActive,
        isDrop = false,
        primary = snap.storeName,
        netPay = snap.netPay,
        estMinutes = snap.estMinutes,
        perMile = snap.perMile,
        confirmedAt = snap.confirmedAt,
        itemsShopped = snap.itemsShopped,
        itemsRemaining = snap.itemsRemaining,
        activity = snap.activity,
    )
}

/** Delivery body — same co-hero shape as Pickup, deliver-by deadline + customer. */
@Composable
private fun DeliveryBody(snap: FlowCardSnapshot.Delivery, isActive: Boolean) {
    TaskBody(
        phaseStartedAt = snap.phaseStartedAt,
        arrivedAt = snap.arrivedAt,
        deadlineMillis = snap.deadlineMillis,
        phaseEndedAt = snap.phaseEndedAt,
        isActive = isActive,
        isDrop = true,
        primary = customerLabel(snap.storeName),
        netPay = snap.netPay,
        estMinutes = snap.estMinutes,
        perMile = snap.perMile,
        confirmedAt = null,
        itemsShopped = null,
        itemsRemaining = null,
        activity = null,
    )
}

/**
 * Shared task-card body for Pickup + Delivery (#324/#460). Two co-heroes:
 * the phase timer (To-go countdown → Dwell count-up once arrived) and the
 * live "Running at $/hr" realized rate (holds until the deadline, then erodes
 * — the drop-it signal). Below: an arrival/deadline caption, the drop-it
 * floor banner, shop pace, and a store/customer detail line. All time-derived
 * values tick off `rememberNow()` while the card is live.
 */
@Composable
private fun TaskBody(
    phaseStartedAt: Long,
    arrivedAt: Long?,
    deadlineMillis: Long?,
    phaseEndedAt: Long?,
    isActive: Boolean,
    isDrop: Boolean,
    primary: String,
    netPay: Double?,
    estMinutes: Double?,
    perMile: Double?,
    confirmedAt: Long?,
    itemsShopped: Int?,
    itemsRemaining: Int?,
    activity: String?,
) {
    val c = AppTheme.colors
    val now = if (isActive) rememberNow().value else (phaseEndedAt ?: phaseStartedAt)
    val arrived = arrivedAt != null
    val verb = if (isDrop) "deliver" else "pickup"
    val overdue = deadlineMillis != null && now > deadlineMillis
    // Live realized $/hr — ticks with `now` (erodes past the deadline), so it is recomputed here
    // each tick from the snapshot's anchors via the TaskEconomics SSOT, never frozen at reduce
    // time. The fixed $/mi companion ([perMile]) is precomputed on the snapshot.
    val hourly = TaskEconomics.projectedHourly(netPay, estMinutes, deadlineMillis, now)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- Co-hero pair: phase timer + live realized $/hr ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // LEFT — phase timer.
            val timerLabel: String
            val timerValue: String
            val timerSub: String?
            val timerColor: Color
            when {
                arrived -> {
                    timerLabel = "Dwell"
                    timerValue = formatCountdown(now - arrivedAt)
                    timerSub = if (isDrop) "at door" else "at store"
                    timerColor = deadlineMillis?.let { deadlineColor(it - arrivedAt) } ?: c.text
                }
                deadlineMillis != null -> {
                    timerLabel = if (overdue) "Overdue" else "To go"
                    timerValue = formatCountdown(deadlineMillis - now)
                    timerSub = null
                    timerColor = deadlineColor(deadlineMillis - now)
                }
                else -> {
                    timerLabel = "Elapsed"
                    timerValue = formatDuration(now - phaseStartedAt)
                    timerSub = "en route"
                    timerColor = c.text
                }
            }
            TaskMetric(
                modifier = Modifier.weight(1f),
                live = isActive,
                label = timerLabel,
                value = timerValue,
                sub = timerSub,
                color = timerColor,
            )
            Box(Modifier.size(width = 1.dp, height = 36.dp).background(c.line))
            // RIGHT — Running at $/hr (erodes past the deadline).
            TaskMetric(
                modifier = Modifier.weight(1f),
                live = isActive && hourly != null,
                label = "Running at",
                value = if (hourly != null) "${Formats.money0(hourly)}/hr${if (overdue) " ↓" else ""}" else "—",
                // Sub line prefers the fixed $/mi efficiency; falls back to the erosion status.
                sub = perMile?.let { "${Formats.money(it)}/mi" }
                    ?: if (hourly == null) null else if (overdue) "dropping" else "on track",
                color = hourly?.let { hourlyColor(it, c) } ?: c.text3,
            )
        }

        // ---- arrival / deadline caption ----
        val caption = buildString {
            if (arrived && deadlineMillis != null) {
                val margin = deadlineMillis - arrivedAt
                append("arrived ${formatCountdown(kotlin.math.abs(margin))} ${if (margin >= 0) "early" else "late"} · ")
            }
            when {
                deadlineMillis == null -> append(if (arrived) "at stop" else "en route")
                overdue -> append("${formatCountdown(now - deadlineMillis)} past $verb-by")
                else -> append("$verb by ${formatTime(deadlineMillis)}")
            }
        }
        Caption(caption)

        // ---- drop-it floor banner: overdue AND the rate has eroded below the floor ----
        if (isActive && overdue && hourly != null && TaskEconomics.belowDropFloor(hourly)) {
            Surface(shape = MaterialTheme.shapes.small, color = c.badBg) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = c.bad, modifier = Modifier.size(16.dp))
                    Text(
                        "Below your floor — no longer worth the wait.",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.bad,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // ---- shop progress (Shop & Deliver): shopped/total + live pace ----
        if (activity == PickupActivity.SHOPPING) {
            val shopped = itemsShopped ?: 0
            val total = shopped + (itemsRemaining ?: 0)
            if (total > 0) {
                val elapsedMs = now - (arrivedAt ?: phaseStartedAt)
                val pace = if (elapsedMs > 0) shopped / (elapsedMs / 60_000.0) else 0.0
                Surface(shape = MaterialTheme.shapes.small, color = c.stPickup.copy(alpha = 0.12f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("shop $shopped/$total", style = AppTheme.num.smNum, color = c.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${Formats.decimal(pace)}/min", style = AppTheme.num.smNum, color = c.stPickup, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ---- detail line — store/customer + lifecycle times ----
        val detail = buildString {
            append(primary)
            arrivedAt?.let { append(" · arrived ${formatTime(it)}") }
            confirmedAt?.let { append(" · picked up ${formatTime(it)}") }
            if (arrivedAt == null && phaseEndedAt != null && !isActive) {
                append(" · ${formatDuration(phaseEndedAt - phaseStartedAt)}")
            }
        }
        Caption(detail)
    }
}

/** One co-hero metric — a label (with a live dot), a big value, and an optional sub. */
@Composable
private fun TaskMetric(
    modifier: Modifier = Modifier,
    live: Boolean,
    label: String,
    value: String,
    sub: String?,
    color: Color,
) {
    val c = AppTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (live) Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.text3, fontWeight = FontWeight.Bold)
        }
        // 24sp — a co-hero size (vs the 30sp single hero) so the two metrics fit
        // side by side in the bubble's width without clipping.
        Text(value, style = AppTheme.num.heroNum.copy(fontSize = 24.sp), color = color, maxLines = 1)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = c.text3, maxLines = 1)
    }
}

/**
 * The $/hr tier → brand Color mapping for the task co-hero (#460). The tier
 * *classification* (the cutoffs) lives in [TaskEconomics]; the UI keeps only
 * the color lookup (audit #6).
 */
private fun hourlyColor(hourly: Double, c: AppColors): Color = when (TaskEconomics.hourlyTier(hourly)) {
    TaskEconomics.HourlyTier.GOOD -> c.good
    TaskEconomics.HourlyTier.WARN -> c.warn
    TaskEconomics.HourlyTier.POOR -> c.bad
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
        HeroBig(Formats.money(snap.totalPay))
        Caption("delivery total")
        snap.parsedPay?.let { pay ->
            Column(
                modifier = Modifier.padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                pay.appPayComponents.forEach { item ->
                    BreakdownRow(item.type, Formats.money(item.amount))
                }
                pay.customerTips.forEach { tip ->
                    BreakdownRow("tip · ${tip.type}", Formats.money(tip.amount))
                }
            }
        }
        snap.sessionEarningsAtCompletion?.let {
            Caption("session ${Formats.money(it)}")
        }
    }
}

// ---------------------------------------------------------------------------
// Primitives
// ---------------------------------------------------------------------------

/** Manual Accept / Decline buttons on the active offer card (#110 Stage 2b). */
@Composable
private fun OfferActionRow(onAccept: () -> Unit, onDecline: () -> Unit) {
    val c = AppTheme.colors
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
        style = AppTheme.num.heroNum,
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
    val c = AppTheme.colors
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
    val c = AppTheme.colors
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
private fun offerExpiryColor(frac: Double, c: AppColors): Color = when {
    frac > 0.45 -> c.good
    frac > 0.2 -> c.warn
    else -> c.bad
}

@Composable
private fun formatTime(millis: Long): String = rememberTimeFormatter().invoke(millis)
