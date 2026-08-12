package cloud.trotter.dashbuddy.ui.main.plan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlan
import cloud.trotter.dashbuddy.domain.analytics.WeeklyPlanGrade
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatMonthDay
import cloud.trotter.dashbuddy.ui.main.analytics.HeatmapGrid
import cloud.trotter.dashbuddy.ui.main.analytics.HeatmapHourAxis
import cloud.trotter.dashbuddy.ui.main.analytics.PatternsModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// ── 4. Where the plan came from (brief §8 item 4) ──────────────────────────

/**
 * The driver's lifetime heatmap with the picked cells outlined — "the plan must be visibly derived
 * from the user's own data, never a black box".
 *
 * It renders the **same** [HeatmapGrid] the Patterns tab does (extracted for exactly this, #979/#981):
 * a lookalike grid would eventually drift from the one the driver recognises, and the recognition is
 * the whole proof.
 */
@Composable
fun PlanProvenanceCard(heatmap: EarningsHeatmap, plan: WeeklyPlan) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.weekly_plan_provenance_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        HeatmapGrid(
            heatmap = heatmap,
            mode = PatternsModel.HeatmapMode.RATE,
            maxForMode = heatmap.maxDollarsPerHour ?: 0.0,
            outlined = { dayIndex, hour -> plan.covers(dayIndex, hour) },
        )
        Spacer(Modifier.height(6.dp))
        HeatmapHourAxis()
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.weekly_plan_provenance_caption),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

// ── 5. Growth rows, present but locked (brief §8 item 5) ───────────────────

/**
 * The two rows the brief asks to be **designed in now so the screen doesn't need reworking later**:
 * area demand (the subscription pull) and a year-over-year comparison.
 *
 * They are deliberately inert — dashed, dimmed, unclickable — and carry **no data at all**. A locked
 * row showing plausible-looking numbers would be the single worst thing on this screen: every other
 * figure here is the driver's own record, and one fabricated neighbour would make all of them suspect.
 * Each says only what it *would* be and what it is waiting on.
 */
@Composable
fun GrowthRows() {
    AreaDemandRow()
    Spacer(Modifier.height(12.dp))
    YearOverYearRow()
}

/**
 * "Demand around you" — the subscription pull, inert and carrying no data.
 *
 * Named separately from [GrowthRows] because the Playbook (#1024 section C) renders this one on its
 * own: it is a *what should I do next* row, which is that screen's whole question. Same copy, one
 * owner — a second hand-written locked row would be a fabricated neighbour to every real figure.
 */
@Composable
fun AreaDemandRow() {
    LockedRow(
        badge = stringResource(R.string.weekly_plan_growth_plus_badge),
        title = stringResource(R.string.weekly_plan_growth_area_title),
        body = stringResource(R.string.weekly_plan_growth_area_body),
        alpha = PLUS_ALPHA,
    )
}

/** "This week last year" — waiting on a year of the driver's own record. */
@Composable
fun YearOverYearRow() {
    LockedRow(
        badge = stringResource(R.string.weekly_plan_growth_year_badge),
        title = stringResource(R.string.weekly_plan_growth_year_title),
        body = stringResource(R.string.weekly_plan_growth_year_body),
        alpha = YEAR_ALPHA,
    )
}

@Composable
private fun LockedRow(badge: String, title: String, body: String, alpha: Float) {
    val c = AppTheme.colors
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha),
        border = BorderStroke(1.dp, c.line),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppChip(text = badge)
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = c.text)
            }
            Spacer(Modifier.height(6.dp))
            Text(text = body, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
    }
}

/** The brief's exact opacities: 60% for the PLUS row, 45% for the year-over-year one. */
private const val PLUS_ALPHA = 0.60f
private const val YEAR_ALPHA = 0.45f

// ── 6. Save (brief §8 item 6) ──────────────────────────────────────────────

/** Save (or update) this week's plan — what the Home pointer reads and next Sunday grades. */
@Composable
fun SavePlanCard(isSaved: Boolean, canSave: Boolean, onSave: () -> Unit) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = onSave, enabled = canSave) {
            Text(
                stringResource(
                    if (isSaved) R.string.weekly_plan_save_update else R.string.weekly_plan_save_button,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.weekly_plan_save_note),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

// ── The grading loop's visible half ────────────────────────────────────────

/**
 * Last week's plan measured against what actually happened — the same four numbers the Sunday
 * notification leads with, so tapping through never shows a different story than the shade did.
 *
 * Stated, never scored: no letter grade, no percentage, no judgement. A week the driver skipped
 * entirely says exactly that.
 */
@Composable
fun LastWeekGradeCard(grade: WeeklyPlanGrade) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.weekly_plan_grade_title, formatMonthDay(grade.weekStart)),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(8.dp))
        if (grade.unworked) {
            Text(
                text = stringResource(R.string.weekly_plan_grade_unworked_format, grade.plannedHours),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
        } else {
            Text(
                text = stringResource(
                    R.string.weekly_plan_grade_hours_format,
                    Formats.decimal(grade.actualHours),
                    grade.plannedHours,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.weekly_plan_grade_money_format,
                    Formats.money(grade.actualKept),
                    Formats.money(grade.projectedKept),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (grade.moneyMet) c.good else c.text2,
            )
        }
        if (grade.keptOutsideWindows > 0.0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.weekly_plan_grade_outside_format,
                    Formats.money(grade.keptOutsideWindows),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
    }
}

/** The pre-data state: say there is no record rather than render an empty plan that looks like a bad week. */
@Composable
fun NoRecordCard() {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.weekly_plan_no_record_title),
            style = MaterialTheme.typography.titleSmall,
            color = c.text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.weekly_plan_no_record_body),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

// ── Shared labels ──────────────────────────────────────────────────────────

/**
 * "Mondays" — the weekday in its recurring-plural form, the shape every evidence line on this screen
 * needs (`your Fridays: …`). The name is localized by `java.time`; the pluralization is its own
 * one-argument string so a translator can replace the English `+s` rule rather than fight code — the
 * same split #977's Home strip uses.
 */
@Composable
internal fun weekdayPlural(dayIndex: Int): String {
    val name = DayOfWeek.of(dayIndex.coerceIn(0, 6) + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())
    return stringResource(R.string.weekly_plan_weekday_plural_format, name)
}

/** "Aug 3" — the week's Monday, through the analytics pager's own month-day label (Principle 5). */
internal fun weekLabel(weekStart: LocalDate): String = formatMonthDay(weekStart)
