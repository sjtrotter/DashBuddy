package cloud.trotter.dashbuddy.feature.bubble.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatCountdown
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.cards.TaskEconomics
import cloud.trotter.dashbuddy.domain.state.PickupActivity
import cloud.trotter.dashbuddy.domain.state.dropoffOrderLabel
import cloud.trotter.dashbuddy.feature.bubble.R

// ---------------------------------------------------------------------------
// Awaiting + Pickup/Delivery (shared task-card) — summary lines and bodies
// (#943 split of FlowCardItem.kt — moves only).
// ---------------------------------------------------------------------------

@Composable
internal fun awaitingSummary(snap: FlowCardSnapshot.Awaiting, isActive: Boolean): String {
    val now = if (isActive) rememberNow().value else (snap.phaseEndedAt ?: snap.phaseStartedAt)
    val elapsed = now - snap.phaseStartedAt
    return if (isActive) stringResource(R.string.flow_card_awaiting_active_format, formatDuration(elapsed))
    else stringResource(R.string.flow_card_awaiting_frozen_format, formatDuration(elapsed))
}

@Composable
internal fun pickupSummary(snap: FlowCardSnapshot.Pickup, isActive: Boolean): String {
    val store = snap.storeName
    val arrivedAt = snap.arrivedAt
    return when {
        isActive -> store
        arrivedAt != null -> stringResource(R.string.flow_card_pickup_arrived_format, store, formatTime(arrivedAt))
        else -> store
    }
}

@Composable
internal fun deliverySummary(snap: FlowCardSnapshot.Delivery, isActive: Boolean): String {
    // #1005: the store's own order, never a customer-shaped label — see [dropoffOrderLabel].
    val order = dropoffOrderLabel(snap.storeName)
    val arrivedAt = snap.arrivedAt
    return when {
        isActive -> order
        arrivedAt != null -> stringResource(R.string.flow_card_delivery_delivered_format, order, formatTime(arrivedAt))
        else -> order
    }
}

@Composable
internal fun AwaitingBody(snap: FlowCardSnapshot.Awaiting, isActive: Boolean) {
    val now = if (isActive) rememberNow().value else (snap.phaseEndedAt ?: snap.phaseStartedAt)
    val elapsed = now - snap.phaseStartedAt
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        HeroBig(formatDuration(elapsed))
        Caption(
            if (isActive) stringResource(R.string.flow_card_since_last_offer)
            else stringResource(R.string.flow_card_before_next_offer)
        )
    }
}

/** Pickup body — the #324/#460 task-card vocabulary (co-hero pair). */
@Composable
internal fun PickupBody(snap: FlowCardSnapshot.Pickup, isActive: Boolean) {
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

/** Delivery body — same co-hero shape as Pickup, deliver-by deadline + the store's own order (#1005). */
@Composable
internal fun DeliveryBody(snap: FlowCardSnapshot.Delivery, isActive: Boolean) {
    TaskBody(
        phaseStartedAt = snap.phaseStartedAt,
        arrivedAt = snap.arrivedAt,
        deadlineMillis = snap.deadlineMillis,
        phaseEndedAt = snap.phaseEndedAt,
        isActive = isActive,
        isDrop = true,
        // #1005: the store's own order, never a customer-shaped label.
        primary = dropoffOrderLabel(snap.storeName),
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
    val verb = if (isDrop) stringResource(R.string.flow_card_verb_deliver) else stringResource(R.string.flow_card_verb_pickup)
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
                    timerLabel = stringResource(R.string.flow_card_timer_dwell_label)
                    timerValue = formatCountdown(now - arrivedAt)
                    timerSub = if (isDrop) stringResource(R.string.flow_card_timer_at_door_sub) else stringResource(R.string.flow_card_timer_at_store_sub)
                    timerColor = deadlineMillis?.let { deadlineColor(it - arrivedAt) } ?: c.text
                }
                deadlineMillis != null -> {
                    timerLabel = if (overdue) stringResource(R.string.flow_card_timer_overdue_label) else stringResource(R.string.flow_card_timer_to_go_label)
                    timerValue = formatCountdown(deadlineMillis - now)
                    timerSub = null
                    timerColor = deadlineColor(deadlineMillis - now)
                }
                else -> {
                    timerLabel = stringResource(R.string.flow_card_timer_elapsed_label)
                    timerValue = formatDuration(now - phaseStartedAt)
                    timerSub = stringResource(R.string.flow_card_timer_en_route_sub)
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
                label = stringResource(R.string.flow_card_running_at_label),
                value = if (hourly != null) {
                    if (overdue) stringResource(R.string.flow_card_running_at_overdue_format, Formats.money0(hourly))
                    else stringResource(R.string.flow_card_hourly_hero_format, Formats.money0(hourly))
                } else "—",
                // Sub line prefers the fixed $/mi efficiency; falls back to the erosion status.
                sub = perMile?.let { stringResource(R.string.flow_card_per_mile_secondary_format, Formats.money(it)) }
                    ?: if (hourly == null) null else if (overdue) stringResource(R.string.flow_card_running_at_dropping_sub)
                    else stringResource(R.string.flow_card_running_at_on_track_sub),
                color = hourly?.let { hourlyColor(it, c) } ?: c.text3,
            )
        }

        // ---- arrival / deadline caption ----
        val caption = buildString {
            if (arrived && deadlineMillis != null) {
                val margin = deadlineMillis - arrivedAt
                append(
                    stringResource(
                        R.string.flow_card_arrived_margin_format,
                        formatCountdown(kotlin.math.abs(margin)),
                        if (margin >= 0) stringResource(R.string.flow_card_margin_early) else stringResource(R.string.flow_card_margin_late),
                    ),
                )
            }
            when {
                deadlineMillis == null -> append(if (arrived) stringResource(R.string.flow_card_at_stop) else stringResource(R.string.flow_card_en_route))
                overdue -> append(stringResource(R.string.flow_card_past_deadline_format, formatCountdown(now - deadlineMillis), verb))
                else -> append(stringResource(R.string.flow_card_by_deadline_format, verb, formatTime(deadlineMillis)))
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
                        stringResource(R.string.flow_card_below_floor_warning),
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
                        Text(
                            stringResource(R.string.flow_card_shop_progress_format, shopped, total),
                            style = AppTheme.num.smNum, color = c.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.flow_card_shop_pace_format, Formats.decimal(pace)),
                            style = AppTheme.num.smNum, color = c.stPickup, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }

        // ---- detail line — store/customer + lifecycle times ----
        val detail = buildString {
            append(primary)
            arrivedAt?.let { append(stringResource(R.string.flow_card_detail_arrived_format, formatTime(it))) }
            confirmedAt?.let { append(stringResource(R.string.flow_card_detail_picked_up_format, formatTime(it))) }
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
        Text(value, style = AppTheme.num.heroNum.copy(fontSize = 24.sp * AppTheme.glance), color = color, maxLines = 1)
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

@Composable
private fun formatTime(millis: Long): String = rememberTimeFormatter().invoke(millis)
