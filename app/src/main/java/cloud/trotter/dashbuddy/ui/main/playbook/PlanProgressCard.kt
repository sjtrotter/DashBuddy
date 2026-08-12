package cloud.trotter.dashbuddy.ui.main.playbook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.analytics.PlanProgress
import cloud.trotter.dashbuddy.domain.analytics.PlanWindowState
import cloud.trotter.dashbuddy.domain.analytics.PlannedWindowProgress
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanSchedule
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatMonthDay
import cloud.trotter.dashbuddy.domain.format.hourRangeLabel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * **This week's plan, with live progress** (#1024 section C) — `5h worked in your windows · $118 kept
 * of the $280 you planned for`, and one hairline row per window that marks itself done as the week runs.
 *
 * This is the visible half of the grading loop: the Sunday notification only earns its place if the
 * plan is worth following in the days before it, and a plan the driver cannot check against reality
 * mid-week is just a suggestion they saw once.
 *
 * **Reactive by rule 2, and on ONE clock.** The state holds the anchor ([grade]); the card reads the
 * device clock through [rememberNow] and derives the local **date and hour from that single instant**,
 * so it re-derives exactly when an hour boundary crosses (a window flipping to *done*) rather than on
 * every tick. Taking the date from a flow while taking the hour from a ticker would let the two
 * disagree across local midnight — reporting "all scheduled hours have passed" during the first minute
 * of a day the plan does not describe — and across a time-zone change (#1024 review F2).
 *
 * **Every state states its reason (§9).** No plan saved → say so and offer to build one, never an empty
 * plan that reads as a bad week. Plan saved but not started → say that, rather than reporting `0h of
 * 12h` as if the driver were already behind. A window whose hours passed with nothing logged says "no
 * time logged" — measured, not scored. And the two hour figures are labelled apart: hours **worked**
 * are measured presence, hours **scheduled** are clock positions; subtracting one from the other is
 * meaningless, so the copy never invites it.
 */
@Composable
fun PlanProgressCard(
    grade: WeeklyPlanGrade?,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    // ONE clock read per tick; both the date and the hour come out of it (F2).
    val now by rememberNow(TICK_MS)
    val localNow by remember { derivedStateOf { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()) } }

    AppCard(modifier = modifier.fillMaxWidth()) {
        // The week has one owner per path: the plan's own frozen weekStart when there is a plan (it is
        // what selected it), else the week the same instant falls in.
        val weekStart: LocalDate = grade?.weekStart ?: WeeklyPlanSchedule.weekStartOf(localNow.toLocalDate())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.playbook_plan_title),
                style = MaterialTheme.typography.labelMedium,
                color = c.text3,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.weekly_plan_week_of_format, formatMonthDay(weekStart)),
                style = MaterialTheme.typography.labelMedium,
                color = c.text3,
            )
        }
        Spacer(Modifier.height(10.dp))

        if (grade == null) {
            NoPlanBody(onOpenPlan)
            return@AppCard
        }

        val progress by remember(grade) {
            derivedStateOf { PlanProgress.of(grade, localNow.toLocalDate(), localNow.hour) }
        }

        PlanHeadline(progress)
        if (progress.windows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            progress.windows.forEachIndexed { index, window ->
                if (index > 0) HorizontalDivider(color = c.line)
                PlanWindowRow(window)
            }
        }
        if (progress.keptOutsideWindows > 0.0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.weekly_plan_grade_outside_format,
                    Formats.money(progress.keptOutsideWindows),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.playbook_plan_open),
            style = MaterialTheme.typography.bodyMedium,
            color = c.accent,
            modifier = Modifier.clickable(
                onClickLabel = stringResource(R.string.playbook_plan_open_click_label),
                role = Role.Button,
                onClick = onOpenPlan,
            ),
        )
    }
}

/**
 * `5h worked in your windows · $118 kept of the $280 you planned for`, plus the schedule line beneath
 * it — or, before the first window, the plain statement that the week hasn't started and what it holds.
 *
 * The two hour figures are named apart on purpose (#1024 review F5): the headline reports hours
 * **worked** (measured presence inside the windows) and the line under it hours **scheduled** (clock
 * positions in the plan). Sharing the word "hours" between them would invite a subtraction whose answer
 * means nothing.
 */
@Composable
private fun PlanHeadline(progress: PlanProgress) {
    val c = AppTheme.colors
    if (progress.notStarted) {
        Text(
            text = stringResource(
                R.string.playbook_plan_not_started_format,
                progress.plannedHours,
                Formats.money(progress.projectedKept),
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = c.text,
        )
        return
    }
    Text(
        text = stringResource(
            R.string.playbook_plan_headline_format,
            Formats.decimal(progress.hoursDone),
            Formats.money(progress.keptSoFar),
            Formats.money(progress.projectedKept),
        ),
        style = MaterialTheme.typography.titleMedium,
        color = c.text,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = if (progress.finished) {
            stringResource(R.string.playbook_plan_all_windows_done, progress.plannedHours)
        } else {
            stringResource(
                R.string.playbook_plan_hours_left_format,
                progress.plannedHoursLeft,
                progress.plannedHours,
            )
        },
        style = MaterialTheme.typography.bodySmall,
        color = c.text3,
    )
}

/** `Mon 9–11a · done · $42 kept` — one hairline row per planned window, with its measured outcome. */
@Composable
private fun PlanWindowRow(progress: PlannedWindowProgress) {
    val c = AppTheme.colors
    val window = progress.window
    val weekday = DayOfWeek.of(window.dayIndex.coerceIn(0, 6) + 1)
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$weekday ${hourRangeLabel(window.startHour, window.endHourExclusive)}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (progress.state == PlanWindowState.UPCOMING) c.text2 else c.text,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = windowDetail(progress),
                style = MaterialTheme.typography.bodySmall,
                color = if (progress.missed) c.warn else c.text3,
            )
        }
        Spacer(Modifier.width(8.dp))
        StateChip(progress.state)
    }
}

/**
 * What a row says under its hours: a finished or running window reports what it actually kept; an
 * upcoming one reports only what it projected (frozen at save time), because it has measured nothing
 * yet and rendering `$0.00 kept` would read as a loss.
 */
@Composable
private fun windowDetail(progress: PlannedWindowProgress): String = when {
    progress.missed -> stringResource(R.string.playbook_plan_window_missed)
    progress.state == PlanWindowState.UPCOMING -> stringResource(
        R.string.playbook_plan_window_projected_format,
        Formats.money(progress.projectedKept),
    )
    else -> stringResource(
        R.string.playbook_plan_window_kept_format,
        Formats.money(progress.actualKept),
        Formats.decimal(progress.actualHours),
    )
}

@Composable
private fun StateChip(state: PlanWindowState) {
    val c = AppTheme.colors
    when (state) {
        PlanWindowState.DONE -> AppChip(text = stringResource(R.string.playbook_plan_window_done))
        PlanWindowState.IN_PROGRESS -> AppChip(
            text = stringResource(R.string.playbook_plan_window_now),
            color = c.good,
            container = c.goodBg,
        )
        // An upcoming window carries no chip — a row of "upcoming" pills is noise, and its detail
        // line already says it has only a projection.
        PlanWindowState.UPCOMING -> Unit
    }
}

/** No plan for this week: say so, and point at the one screen that can fix it. */
@Composable
private fun NoPlanBody(onOpenPlan: () -> Unit) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.playbook_plan_none_title),
            style = MaterialTheme.typography.bodyLarge,
            color = c.text,
        )
        Text(
            text = stringResource(R.string.playbook_plan_none_body),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Text(
            text = stringResource(R.string.playbook_plan_none_action),
            style = MaterialTheme.typography.bodyMedium,
            color = c.accent,
            modifier = Modifier.clickable(
                onClickLabel = stringResource(R.string.playbook_plan_open_click_label),
                role = Role.Button,
                onClick = onOpenPlan,
            ),
        )
    }
}

/**
 * One minute. The only thing the ticker drives here is an hour boundary (a window flipping to *done*),
 * so a per-second tick would recompose 60× for no visible change — and a per-hour tick would be late by
 * up to an hour on the one transition the card exists to show.
 */
private const val TICK_MS = 60_000L
