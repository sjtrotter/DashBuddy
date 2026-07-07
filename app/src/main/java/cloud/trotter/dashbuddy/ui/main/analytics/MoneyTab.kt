package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppBar
import cloud.trotter.dashbuddy.core.designsystem.component.AppBarChart
import cloud.trotter.dashbuddy.core.designsystem.component.AppCallout
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import java.time.format.TextStyle
import java.util.Locale

/** Placeholder shown for a rate figure that has no measurable denominator yet (dashboard parity). */
private const val EMPTY_VALUE = "—"

/** Below this the unattributed pay is effectively zero — no callout (avoids a "$0.00" flag). */
private const val UNATTRIBUTED_EPSILON = 0.005

/**
 * Money tab v1 (#315 H1): the frozen-net earnings review for the selected period, top→bottom —
 * earnings hero, true-net waterfall (3- or 4-step per [WaterfallModel], #659), stat tiles, the
 * unattributed-pay review flag, top stores, and recent dashes. Pure data in / [PeriodEconomics] +
 * record lists, no side effects. Every string routes through the [Formats]/[formatShortDate] SSOT.
 */
@Composable
fun MoneyTab(
    economics: PeriodEconomics,
    topStores: List<StoreEconomics>,
    recentSessions: List<SessionRecord>,
    dailyEarnings: List<DailyEarnings>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        EarningsHero(economics)
        // Hidden for Today/Lifetime (one bar / unbounded window) — the repository returns an empty list.
        if (dailyEarnings.isNotEmpty()) EarningsByDayCard(dailyEarnings)
        TrueNetWaterfall(economics)
        StatTiles(economics)
        if (economics.unattributedPay > UNATTRIBUTED_EPSILON) {
            AppCallout(
                text = "${Formats.money(economics.unattributedPay)} not attributed to any " +
                    "delivery — bonuses/adjustments; review coming (#650)",
                container = AppTheme.colors.warnBg,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TopStoresCard(topStores)
        RecentDashesCard(recentSessions, onOpenSession)
    }
}

/** Gross headline + True-Net and Net/hr chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EarningsHero(economics: PeriodEconomics) {
    val c = AppTheme.colors
    val netColor = if (economics.netProfit >= 0.0) c.good else c.bad
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "GROSS EARNINGS", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(4.dp))
        Text(text = Formats.money(economics.grossEarnings), style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppChip(
                text = "True net ${Formats.money(economics.netProfit)}",
                color = netColor,
                container = if (economics.netProfit >= 0.0) c.goodBg else c.badBg,
            )
            AppChip(
                text = "Net/hr ${economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE}",
                color = c.accent,
                container = c.accentDim,
            )
        }
    }
}

/**
 * Earnings-by-day bar chart (#315 H6): one bar per local calendar day of the period, gap days at
 * zero, the best day highlighted. Session-anchored (#655) — a dash's whole gross sits on its start
 * day. Only rendered when [days] is non-empty (Today/Lifetime pass an empty list; see [MoneyTab]).
 */
@Composable
private fun EarningsByDayCard(days: List<DailyEarnings>) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "EARNINGS BY DAY", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (days.all { it.gross <= 0.0 }) {
            EmptyRow("No earnings in this period yet.")
        } else {
            // Highlight the first day that hit the period's peak gross (only when someone earned).
            val bestDay = days.maxByOrNull { it.gross }?.takeIf { it.gross > 0.0 }?.date
            val isWeek = days.size == 7
            val bars = days.map { day ->
                AppBar(
                    label = dayLabel(day, isWeek),
                    value = day.gross.toFloat(),
                    highlight = day.date == bestDay,
                )
            }
            AppBarChart(bars = bars, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                text = "gross per day · sessions count on their start day",
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
    }
}

/**
 * Bar label: for a 7-day week the locale's narrow day-of-week name; for a month-length list only the
 * milestone days (1, 5, 10, 15, 20, 25, 30) carry their day-of-month number — 31 labels won't fit.
 */
private val MONTH_LABEL_DAYS = setOf(1, 5, 10, 15, 20, 25, 30)

private fun dayLabel(day: DailyEarnings, isWeek: Boolean): String =
    if (isWeek) {
        day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault())
    } else {
        day.date.dayOfMonth.let { if (it in MONTH_LABEL_DAYS) it.toString() else "" }
    }

/**
 * Pure decision logic for the true-net waterfall's step count (#659) — kept Compose-free so it's
 * unit-testable in isolation from rendering.
 *
 * The waterfall renders **4-step** (Gross → −Fuel → −Non-fuel → Net) only when the frozen
 * fuel/non-fuel split is trustworthy for the whole period: both sums are non-null AND they
 * reconcile against the period's derived operating cost (`gross − net`) within a tolerance. A
 * period that mixes `OFFER_FROZEN` rows with pre-split fallback rows has fuel+non-fuel covering
 * only the frozen subset, so the sums fall short of `gross − net` — that shortfall IS the coverage
 * signal, no separate flag needed. When the guard fails, this silently returns the exact **3-step**
 * (Gross → −Operating cost → Net) shape instead — no partial-coverage note is shown; the fallback
 * keeps the surface honest without cluttering it with a caveat (see PR for the tradeoff).
 */
object WaterfallModel {

    /**
     * One row of the rendered waterfall. [amount] is non-negative for [Role.COST] on the 4-step
     * path by construction (frozen per-mile rates × floored miles); the 3-step fallback's derived
     * cost (`gross − net`) CAN go negative in the reported<delivered shape (#662-F1) — the bar
     * renders zero-width and the signed amount displays honestly.
     */
    data class Step(val role: Role, val label: String, val amount: Double)

    enum class Role { GROSS, COST, NET }

    /** Whichever is larger wins: a flat cent floor for small periods, 1% for large ones. */
    private const val RELATIVE_TOLERANCE = 0.01
    private const val ABSOLUTE_TOLERANCE_DOLLARS = 0.50

    fun from(economics: PeriodEconomics): List<Step> {
        val gross = economics.grossEarnings
        val net = economics.netProfit
        val cost = gross - net
        val fuel = economics.fuelCost
        val nonFuel = economics.nonFuelCost

        if (fuel != null && nonFuel != null) {
            val tolerance = maxOf(cost * RELATIVE_TOLERANCE, ABSOLUTE_TOLERANCE_DOLLARS)
            if (kotlin.math.abs((fuel + nonFuel) - cost) <= tolerance) {
                return listOf(
                    Step(Role.GROSS, "Gross", gross),
                    Step(Role.COST, "Fuel", fuel),
                    Step(Role.COST, "Non-fuel (wear, depreciation, fixed)", nonFuel),
                    Step(Role.NET, "Net", net),
                )
            }
        }

        return listOf(
            Step(Role.GROSS, "Gross", gross),
            Step(Role.COST, "Operating cost", cost),
            Step(Role.NET, "Net", net),
        )
    }
}

/**
 * The true-net waterfall: gross → −cost step(s) → net, 3- or 4-step per [WaterfallModel.from]
 * (#659). Unattributed pay is net-additive, so it flows through consistently regardless of step
 * count. Each row is a proportional bar against the period's largest magnitude — the honest
 * visual of "what stayed".
 */
@Composable
private fun TrueNetWaterfall(economics: PeriodEconomics) {
    val c = AppTheme.colors
    val steps = WaterfallModel.from(economics)
    // Bars scale against the largest magnitude so a net > gross (net-additive unattributed) still
    // renders sanely and a zero period draws empty bars instead of dividing by zero.
    val scale = (listOf(0.0) + steps.map { it.amount }).max().takeIf { it > 0.0 } ?: 1.0

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "TRUE NET", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        steps.forEachIndexed { index, step ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            val (amountText, barColor, amountColor) = when (step.role) {
                WaterfallModel.Role.GROSS -> Triple(Formats.money(step.amount), c.accent, c.text)
                WaterfallModel.Role.COST -> Triple("−${Formats.money(step.amount)}", c.bad, c.text2)
                WaterfallModel.Role.NET -> Triple(
                    Formats.money(step.amount),
                    c.good,
                    if (step.amount >= 0.0) c.good else c.bad,
                )
            }
            WaterfallRow(step.label, amountText, (step.amount / scale).toFloat(), barColor, amountColor)
        }
    }
}

@Composable
private fun WaterfallRow(label: String, amount: String, fraction: Float, barColor: Color, amountColor: Color) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = c.text2, modifier = Modifier.width(110.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(c.surface3),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(barColor),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = amount,
            style = AppTheme.num.smNum,
            color = amountColor,
            modifier = Modifier.width(88.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** 2×2 stat tiles: Net $/hr · Net $/mi · Miles · Deliveries. */
@Composable
private fun StatTiles(economics: PeriodEconomics) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = "Net/hr",
                value = economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = "Net/mi",
                value = economics.netPerMile?.let { Formats.money(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = "Miles",
                value = Formats.decimal(economics.totals.miles),
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = "Deliveries",
                value = Formats.commaInt(economics.totals.deliveries),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Top-earning stores for the period. Store names are merchants — fine to render (Principle 7 governs logs). */
@Composable
private fun TopStoresCard(stores: List<StoreEconomics>) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "TOP STORES", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (stores.isEmpty()) {
            EmptyRow("No store earnings in this period yet.")
        } else {
            stores.forEachIndexed { index, store ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = store.storeName ?: "Unknown store",
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                        )
                        Text(
                            text = "${Formats.commaInt(store.deliveries)} " +
                                if (store.deliveries == 1) "delivery" else "deliveries",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.text3,
                        )
                    }
                    Text(
                        text = Formats.money(store.net),
                        style = AppTheme.num.smNum,
                        color = if (store.net >= 0.0) c.good else c.bad,
                    )
                }
            }
        }
    }
}

/**
 * Recent dashes, newest first. Sessions don't carry a frozen net, so the money column shows the
 * platform-reported earnings (an em dash until a summary is seen), with a small "+cash" line below it
 * when the dash has driver-entered cash tips (#688 F7) — every sibling gross surface (hero, per-day
 * chart, drill-down) is cash-inclusive, so this keeps the recent-dashes row from showing a different
 * gross one tap away. Cash is shown ADDITIVELY, never folded into the reported number (the label stays
 * honest). Each row is tappable → the read-only per-dash drill-down ([onOpenSession], #650).
 */
@Composable
private fun RecentDashesCard(sessions: List<SessionRecord>, onOpenSession: (String) -> Unit) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "RECENT SESSIONS", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (sessions.isEmpty()) {
            EmptyRow("No sessions recorded yet.")
        } else {
            sessions.forEachIndexed { index, session ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSession(session.sessionId) },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = formatShortDate(session.startedAt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                        )
                        Text(
                            text = "${Formats.commaInt(session.deliveries)} " +
                                if (session.deliveries == 1) "delivery" else "deliveries",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.text3,
                        )
                    }
                    // Platform label is registry-resolved (never a literal) — Principle 8.
                    AppChip(text = session.platform.shortName.ifEmpty { session.platform.displayName })
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = session.reportedEarnings?.let { Formats.money(it) } ?: EMPTY_VALUE,
                            style = AppTheme.num.smNum,
                            color = c.text,
                        )
                        // Additive-only cash marker (#688 F7) — never folded into the reported number.
                        if (session.cashTips > UNATTRIBUTED_EPSILON) {
                            Text(
                                text = "+${Formats.money(session.cashTips)} cash",
                                style = MaterialTheme.typography.bodySmall,
                                color = c.good,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text3,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
