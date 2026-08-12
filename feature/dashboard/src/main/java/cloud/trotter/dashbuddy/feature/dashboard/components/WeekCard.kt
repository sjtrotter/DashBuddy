package cloud.trotter.dashbuddy.feature.dashboard.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppSparkline
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.NetDelta
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.dashboard.R

/**
 * **This week** (#1024 section D4) — one card, up to three hairline-separated rows:
 *  1. the pay week's kept money, its delta against last week, the per-day net sparkline, and
 *     `Recap →` into the Analytics hub anchored on the same week;
 *  2. the driver's saved **weekly plan** pointer, rendered only when a plan with real windows exists;
 *  3. the week's **review items**, supplied by the host as [reviewContent] and rendered only when the
 *     week actually raises a flag.
 *
 * It is the merge of #977's `WeekRecapCard`, #981's `WeeklyPlanPointerRow` and the review card. All
 * three described the same seven days in three stacked containers of equal weight; one card of rows
 * says it once (#1024 rule 3). Every row is built from the shared [DashboardRow] vocabulary, so the
 * hairline, the action affordance and the tones cannot drift between them.
 *
 * **Why [reviewContent] is a slot rather than a parameter.** The review flags, their thresholds and
 * their copy have exactly ONE owner — the analytics hub's `ReviewFlags`/`reviewTexts` in `:app`
 * (Principle 5). A feature module cannot reach an `:app` owner and must never grow a second copy of
 * a threshold, so the host composes those rows and hands them down; this module owns only where they
 * sit. Null means "this week is clean" — the hairline above the section goes with the section, so an
 * empty review never leaves a divider hanging over nothing.
 *
 * Every number is the read-model's own: net is the frozen per-delivery net (an economy edit never
 * rewrites it — §9), the sparkline plots [DailyEarnings.net] so a day whose miles ate it can't look
 * good, the delta routes through the shared [NetDelta] rule (which states "no comparison" rather than
 * dividing by an empty week, and reads a sub-half-percent wobble as flat rather than as a trend), and
 * the plan row's three figures come straight off the frozen [SavedWeeklyPlan] the driver committed to.
 * The old `Net frozen at accept-time costs` note is gone (#1024 D4): that disclosure now lives once
 * per screen in the host's `How these numbers work` footer instead of once per card.
 *
 * Stateless, data-in / lambdas-out: nothing here ticks (a settled week's total has nothing to tick),
 * and each row raises only its own lambda — the host owns what they mean. Only the rows are
 * clickable, never the card, so a tap always lands on the thing the driver aimed at.
 */
@Composable
fun WeekCard(
    economics: PeriodEconomics,
    previousEconomics: PeriodEconomics?,
    dailyEarnings: List<DailyEarnings>,
    plan: SavedWeeklyPlan?,
    onOpenRecap: () -> Unit,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier,
    reviewContent: (@Composable () -> Unit)? = null,
) {
    AppCard(modifier = modifier.fillMaxWidth()) {
        WeekRow(
            economics = economics,
            previousEconomics = previousEconomics,
            dailyEarnings = dailyEarnings,
            onOpenRecap = onOpenRecap,
        )

        // A decoded plan can legally carry zero windows — the codec keeps the row when every window
        // fails validation — and "0 target hours across 0 windows" is not a plan. Treated as no plan
        // at the first consumer, the same convention the Playbook applies (#1024 part 1).
        if (plan != null && plan.windows.isNotEmpty()) {
            Hairline()
            PlanRow(plan = plan, onOpenPlan = onOpenPlan)
        }

        if (reviewContent != null) {
            Hairline()
            reviewContent()
        }
    }
}

/** Row 1 — kept money, the honest delta, the per-day net line, and the way into the hub. */
@Composable
private fun WeekRow(
    economics: PeriodEconomics,
    previousEconomics: PeriodEconomics?,
    dailyEarnings: List<DailyEarnings>,
    onOpenRecap: () -> Unit,
) {
    val c = AppTheme.colors
    // ONE delta computation feeding one dispatch: the wording and the tone are two views of the same
    // decision, and two parallel `when`s over the same enum are two things that must agree forever.
    val delta = deltaLine(NetDelta.delta(economics.netProfit, previousEconomics?.netProfit))
    // The per-day series is derived, not held: recomputing it on an unrelated recomposition would
    // hand AppSparkline a new list every frame.
    val nets = remember(dailyEarnings) { dailyEarnings.map { it.net } }

    DashboardRow(
        actionLabel = stringResource(R.string.dashboard_week_recap_action),
        onClick = onOpenRecap,
        // Top, not centre: this row is tall (a hero figure, a delta and possibly a chart), and an
        // action label floating at its vertical middle reads as belonging to the chart.
        actionAlignment = Alignment.Top,
    ) {
        DashboardRowTitle(stringResource(R.string.dashboard_week_title))
        Spacer(Modifier.height(TITLE_GAP))
        Text(
            text = Formats.money(economics.netProfit),
            style = AppTheme.num.xlNum,
            color = if (economics.netProfit >= 0.0) c.good else c.bad,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = delta.text,
            style = MaterialTheme.typography.bodySmall,
            color = delta.color,
        )

        // Kept money per day (§7.3). Hidden below two points: a one-point "line" is a shape with no
        // trend in it, which would read as information the week doesn't contain.
        if (nets.size >= 2) {
            Spacer(Modifier.height(10.dp))
            AppSparkline(values = nets, color = c.accent)
        }
    }
}

/**
 * Row 2 — `Your week is planned · 12 target hours across 4 windows · $280 projected`.
 *
 * The figures are the frozen [SavedWeeklyPlan]'s own — the numbers the driver committed to, not a
 * re-derivation that might have moved under them since — and "projected" is the word the row uses,
 * never "you'll earn" (§9).
 */
@Composable
private fun PlanRow(plan: SavedWeeklyPlan, onOpenPlan: () -> Unit) {
    DashboardRow(
        actionLabel = stringResource(R.string.dashboard_weekly_plan_pointer_action),
        onClick = onOpenPlan,
    ) {
        Text(
            text = stringResource(R.string.dashboard_weekly_plan_pointer_title),
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.text,
        )
        Spacer(Modifier.height(TITLE_GAP))
        DashboardRowLine(
            stringResource(
                R.string.dashboard_weekly_plan_pointer_detail_format,
                plan.totalHours,
                plan.windows.size,
                Formats.money0(plan.projectedKept),
            ),
        )
    }
}

/** The delta's wording and its tone, resolved together. */
private data class DeltaLine(val text: String, val color: Color)

/** "▲ 12% vs last week" and its honest alternatives — one `when`, both outputs. */
@Composable
private fun deltaLine(delta: NetDelta.Delta): DeltaLine {
    val c = AppTheme.colors
    return when (delta.direction) {
        // A pay week always HAS a predecessor, so NONE can only mean the previous week's read has not
        // landed yet — say that rather than implying a comparison was made.
        NetDelta.Direction.NONE -> DeltaLine(stringResource(R.string.dashboard_week_delta_none), c.text3)
        NetDelta.Direction.FROM_ZERO ->
            DeltaLine(stringResource(R.string.dashboard_week_delta_from_nothing), c.good)
        NetDelta.Direction.FLAT -> DeltaLine(stringResource(R.string.dashboard_week_delta_flat), c.text3)
        NetDelta.Direction.UP -> DeltaLine(
            stringResource(R.string.dashboard_week_delta_up_format, Formats.percent(delta.fraction ?: 0.0)),
            c.good,
        )
        NetDelta.Direction.DOWN -> DeltaLine(
            stringResource(
                R.string.dashboard_week_delta_down_format,
                Formats.percent(kotlin.math.abs(delta.fraction ?: 0.0)),
            ),
            c.bad,
        )
    }
}
