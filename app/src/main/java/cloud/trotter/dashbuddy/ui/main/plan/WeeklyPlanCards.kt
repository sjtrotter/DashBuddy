package cloud.trotter.dashbuddy.ui.main.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.PlanTarget
import cloud.trotter.dashbuddy.domain.analytics.PlannedWindow
import cloud.trotter.dashbuddy.domain.analytics.UnpickedDay
import cloud.trotter.dashbuddy.domain.analytics.UnpickedReason
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanner
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.hourRangeLabel

// ── 1. Target selector (brief §8 item 1) ───────────────────────────────────

/**
 * `8h · 12h · 20h · $ goal` — what the driver is asking the planner for.
 *
 * The three hour presets come from [PlanTarget.HOUR_PRESETS] (one owner for the option set), and the
 * dollar goal opens a prompt rather than pretending to be a fourth preset: it is a number only the
 * driver knows.
 */
@Composable
fun TargetSelectorCard(target: PlanTarget, onSelect: (PlanTarget) -> Unit) {
    val c = AppTheme.colors
    var showGoalPrompt by rememberSaveable { mutableStateOf(false) }

    val hourLabels = PlanTarget.HOUR_PRESETS.map { stringResource(R.string.weekly_plan_target_hours_format, it) }
    val goalLabel = stringResource(R.string.weekly_plan_target_dollars)
    val options = hourLabels + goalLabel
    val selectedLabel = when (target) {
        is PlanTarget.Hours -> hourLabels.getOrNull(PlanTarget.HOUR_PRESETS.indexOf(target.hours)) ?: goalLabel
        is PlanTarget.Dollars -> goalLabel
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.weekly_plan_target_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        AppSegmented(
            options = options,
            selected = selectedLabel,
            onSelect = { label ->
                // Selection stays keyed off the option INDEX, never a resolved-string comparison
                // that could collide across locales (#428 Half A).
                val index = options.indexOf(label)
                if (index in PlanTarget.HOUR_PRESETS.indices) {
                    onSelect(PlanTarget.Hours(PlanTarget.HOUR_PRESETS[index]))
                } else {
                    showGoalPrompt = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (target is PlanTarget.Dollars) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.weekly_plan_target_dollars_selected, Formats.money(target.amount)),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
    }

    if (showGoalPrompt) {
        DollarGoalDialog(
            initial = (target as? PlanTarget.Dollars)?.amount,
            onDismiss = { showGoalPrompt = false },
            onConfirm = {
                onSelect(PlanTarget.Dollars(it))
                showGoalPrompt = false
            },
        )
    }
}

@Composable
private fun DollarGoalDialog(initial: Double?, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    // The field's own filter admits only digits and `.`, so the seed must be a BARE number — hence
    // `decimal(digits = 0)`, the formatting SSOT's no-currency arity, rather than money0 with its `$`
    // stripped off the front (#1034: money0 now renders a negative as `-$50`, which `removePrefix`
    // would silently leave intact; a goal is non-negative by construction, but the seed should not
    // depend on that). Byte-identical to the old output for every value the dialog can produce.
    var text by rememberSaveable { mutableStateOf(initial?.let { Formats.decimal(it, digits = 0) } ?: "") }
    val amount = text.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.weekly_plan_goal_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.weekly_plan_goal_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    label = { Text(stringResource(R.string.weekly_plan_goal_dialog_label)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { amount?.let(onConfirm) },
                enabled = amount != null && amount > 0.0,
            ) { Text(stringResource(R.string.weekly_plan_goal_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.weekly_plan_goal_dialog_cancel)) }
        },
    )
}

// ── 2. Headline value + the plan-vs-random comparison (brief §8 item 2) ────

/**
 * `12 hours on your best windows ≈ $280 kept`, and directly below it what the same hours are worth
 * placed at random.
 *
 * **The comparison is never dropped.** Brief §8 calls it "the whole pitch", and it is also the only
 * thing that makes the headline honest: a projection with nothing to compare it against invites the
 * driver to read it as money the app is promising them. When the record can't produce a baseline, the
 * card says *that* — it never shows the headline alone.
 */
@Composable
fun PlanValueCard(plan: WeeklyPlan) {
    val c = AppTheme.colors
    val hours = stringResource(R.string.weekly_plan_hours_format, plan.totalHours)

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.weekly_plan_value_headline_format, hours, Formats.money(plan.projectedKept)),
            style = MaterialTheme.typography.titleMedium,
            color = c.text,
        )
        Spacer(Modifier.height(8.dp))

        val randomKept = plan.randomKept
        val worth = plan.planWorth
        Text(
            text = when {
                randomKept == null || worth == null ->
                    stringResource(R.string.weekly_plan_value_no_baseline)

                worth > 0.0 -> stringResource(
                    R.string.weekly_plan_value_comparison_format,
                    hours, Formats.money(randomKept), Formats.money(worth),
                )

                else -> stringResource(
                    R.string.weekly_plan_value_comparison_no_gain_format,
                    hours, Formats.money(randomKept),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.text2,
        )

        if (plan.shortfallHours > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.weekly_plan_value_shortfall_format,
                    plan.totalHours,
                    (plan.target as? PlanTarget.Hours)?.hours ?: plan.totalHours,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        if (plan.hasUnpricedHours) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.weekly_plan_value_unpriced_format,
                    plan.totalHours - plan.pricedHours,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }

        Spacer(Modifier.height(8.dp))
        // §9's projection rule, verbatim and unconditional — rendered on every state of this card.
        Text(
            text = stringResource(R.string.weekly_plan_provenance),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

// ── 3. Suggested windows (brief §8 item 3) ────────────────────────────────

/**
 * One row per placed window — weekday, time range, the evidence behind it, and what it projects —
 * followed by the weekdays that were NOT picked, dimmed, each with the measured reason.
 *
 * **Interaction.** Swipe a row away to drop it (the planner re-fills from the next-best hours it can
 * stand behind), and nudge it an hour earlier or later with the arrows. The brief asks for "drag to
 * move a window"; in a vertical list of rows a drag gesture reads as *reordering the list*, which is
 * the one thing moving a window must not mean — the arrows say "move in time" unambiguously, work
 * one-handed, and produce exactly the same [cloud.trotter.dashbuddy.domain.analytics.PlanEdit].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestedWindowsCard(
    plan: WeeklyPlan,
    onMove: (PlannedWindow, Int) -> Unit,
    onDrop: (PlannedWindow) -> Unit,
    onUndo: () -> Unit,
) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.weekly_plan_windows_title),
                style = MaterialTheme.typography.labelMedium,
                color = c.text3,
                modifier = Modifier.weight(1f),
            )
            if (plan.edits.isNotEmpty()) {
                TextButton(onClick = onUndo) { Text(stringResource(R.string.weekly_plan_undo)) }
            }
        }
        Spacer(Modifier.height(6.dp))

        if (plan.windows.isEmpty()) {
            Text(
                text = stringResource(R.string.weekly_plan_windows_none),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text2,
            )
        }

        plan.windows.forEach { window ->
            key(window) {
                SwipeToDismissBox(
                    state = rememberSwipeToDismissBoxState(),
                    // Keyed by the window, so the state is per-row and a dropped row takes its own
                    // swipe state with it rather than leaving it on whatever slides up into its place.
                    onDismiss = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) onDrop(window)
                    },
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.badBg)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.weekly_plan_window_drop),
                                tint = c.bad,
                            )
                        }
                    },
                ) {
                    WindowRow(window = window, onMove = onMove)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        plan.notPicked.forEach { day ->
            NotPickedRow(day)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WindowRow(window: PlannedWindow, onMove: (PlannedWindow, Int) -> Unit) {
    val c = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "${weekdayPlural(window.dayIndex)} ${hourRangeLabel(window.startHour, window.endHourExclusive)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                )
                if (window.isBest) {
                    AppChip(
                        text = stringResource(R.string.weekly_plan_window_best),
                        color = c.good,
                        container = c.goodBg,
                    )
                }
                if (window.moved) {
                    AppChip(text = stringResource(R.string.weekly_plan_window_moved))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = evidenceLine(window),
                style = MaterialTheme.typography.bodySmall,
                color = if (window.dollarsPerHour == null || window.hasThinEvidence) c.warn else c.text3,
            )
        }
        Text(
            text = if (window.dollarsPerHour == null) {
                stringResource(R.string.weekly_plan_window_unpriced_value)
            } else {
                Formats.money(window.projectedKept)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = c.text,
        )
        IconButton(onClick = { onMove(window, -1) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.weekly_plan_window_earlier),
                tint = c.text3,
            )
        }
        IconButton(onClick = { onMove(window, +1) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.weekly_plan_window_later),
                tint = c.text3,
            )
        }
    }
}

/**
 * `your Fridays: $26.40/hr over 6 Fridays` — the brief's evidence line, measured in the unit the
 * record can actually count.
 *
 * The brief writes "over 14 dashes"; a dash count is structurally unrecoverable from the aggregates
 * (session spans are unioned and apportioned before any of this exists), so the honest unit is the
 * number of that weekday's own days behind the median — the same hours-not-dashes discipline #977's
 * Home strip settled on. A window with no record behind it says so instead of quoting anything.
 */
@Composable
private fun evidenceLine(window: PlannedWindow): String {
    val weekday = weekdayPlural(window.dayIndex)
    val rate = window.dollarsPerHour ?: return stringResource(R.string.weekly_plan_window_no_evidence)
    return stringResource(
        R.string.weekly_plan_window_evidence_format,
        weekday,
        Formats.money(rate),
        window.sampleCount,
        weekday,
    )
}

/** A weekday the plan left alone, dimmed, with the reason it was left alone. */
@Composable
private fun NotPickedRow(day: UnpickedDay) {
    val c = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(NOT_PICKED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = weekdayPlural(day.dayIndex),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text2,
            )
            Text(
                text = unpickedReasonText(day),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        AppChip(text = stringResource(R.string.weekly_plan_not_picked))
    }
}

@Composable
private fun unpickedReasonText(day: UnpickedDay): String {
    val weekday = weekdayPlural(day.dayIndex)
    return when (day.reason) {
        UnpickedReason.NO_HISTORY -> stringResource(R.string.weekly_plan_reason_no_history, weekday)
        UnpickedReason.THIN_HISTORY ->
            stringResource(R.string.weekly_plan_reason_thin_format, day.daysOnRecord, weekday)

        UnpickedReason.NO_CONTIGUOUS_RUN ->
            stringResource(R.string.weekly_plan_reason_no_run_format, WeeklyPlanner.MIN_WINDOW_HOURS, weekday)

        UnpickedReason.NO_POSITIVE_RATE -> stringResource(R.string.weekly_plan_reason_no_positive, weekday)
        UnpickedReason.TARGET_ALREADY_MET -> stringResource(R.string.weekly_plan_reason_target_met)
        UnpickedReason.REMOVED_BY_YOU -> stringResource(R.string.weekly_plan_reason_removed)
    }
}

/** Windows the plan didn't pick stay legible but visibly out of it — the brief's dimmed rows. */
private const val NOT_PICKED_ALPHA = 0.55f
