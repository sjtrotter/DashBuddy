package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppBar
import cloud.trotter.dashbuddy.core.designsystem.component.AppBarChart
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.format.formatWeekdayMonthDay
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.ui.components.HairlineDivider
import cloud.trotter.dashbuddy.ui.components.NetBar
import cloud.trotter.dashbuddy.ui.components.PatternsModel
import java.time.format.TextStyle
import java.util.Locale

/**
 * **The rates and the days they came from** — earnings-by-day (#315 H6, made **tappable** by #973 /
 * brief §4.2) with the three per-something rates now sitting above the chart (#1024 B3).
 *
 * The rates were `RateTiles`, a container of five `AppStatTile`s that was the tab's second card and
 * explained nothing on its own: `Net/hr` and `Net/mi` are the window's kept money over a denominator,
 * and the chart directly below was where that money actually came from. As inline label/value rows
 * inside this card they cost one hairline instead of a whole bordered surface, and they read as what
 * they are — a summary of the series underneath. The tiles' other two figures (miles, deliveries) are
 * gone from here entirely: they are measured denominators, and the recap hero's facts line owns them.
 *
 * The chart itself: one bar per local calendar day of the window, gap days at zero, the best day
 * highlighted. Session-anchored (#655) — a dash's whole gross sits on its start day; a "(No session)"
 * delivery counts on its own completion day (the caption states this). The chart half is skipped when
 * [days] is empty (a single-day, Lifetime, or over-long window returns an empty axis) — but the CARD
 * still renders, because the rates are window-wide facts that exist for exactly those windows too.
 *
 * Tapping a bar selects it and prints its day, gross and delivery count below the chart — the brief's
 * "tooltip", rendered inline rather than as a floating popup so it is reachable by touch, keyboard and
 * screen reader alike, and so it never covers the bars it describes. Tapping the selected bar again
 * clears it. Selection is view-local (it survives nothing and needs to survive nothing), so it lives
 * in a `remember` here, not in the hub's UiState.
 */
@Composable
fun EarningsByDayCard(
    economics: PeriodEconomics,
    days: List<DailyEarnings>,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    AppCard(modifier = modifier.fillMaxWidth()) {
        RateRows(economics)
        if (days.isEmpty()) return@AppCard

        HairlineDivider(gap = 14.dp)
        Text(
            text = stringResource(R.string.money_tab_earnings_by_day_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        if (days.all { it.gross <= 0.0 }) {
            EmptyRow(stringResource(R.string.money_tab_no_earnings_yet))
        } else {
            // Highlight the first day that hit the window's peak gross (only when someone earned).
            val bestDay = days.maxByOrNull { it.gross }?.takeIf { it.gross > 0.0 }?.date
            val isWeek = days.size == 7
            val bars = days.map { day ->
                AppBar(
                    label = dayLabel(day, isWeek),
                    value = day.gross.toFloat(),
                    highlight = day.date == bestDay,
                )
            }
            AppBarChart(
                bars = bars,
                modifier = Modifier.fillMaxWidth(),
                onBarClick = { index -> selected = if (selected == index) null else index },
                selectedIndex = selected,
                clickLabel = stringResource(R.string.money_tab_earnings_by_day_bar_click_label),
            )
            Spacer(Modifier.height(8.dp))
            val day = selected?.let(days::getOrNull)
            if (day != null) {
                Text(
                    text = stringResource(
                        R.string.money_tab_earnings_by_day_detail_format,
                        formatWeekdayMonthDay(day.date),
                        Formats.money(day.gross),
                        Formats.commaInt(day.deliveries),
                        if (day.deliveries == 1) {
                            stringResource(R.string.time_tab_delivery_singular)
                        } else {
                            stringResource(R.string.time_tab_delivery_plural)
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = stringResource(R.string.money_tab_earnings_by_day_caption),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
    }
}

/**
 * Per hour / per mile / per drop as three inline label/value rows (#973 / brief §4.2, restyled from
 * `AppStatTile`s by #1024 B3).
 *
 * Every rate is **kept** money over a measured denominator, and each stays `null` — rendered as the
 * em-dash placeholder — until its denominator exists: the `NetProfit` discipline, so a window with no
 * logged miles shows "—" rather than a fabricated `$0.00/mi`. Per-drop uses the same rule, computed
 * here against the delivery count because it is the one rate the read model does not already carry.
 */
@Composable
private fun RateRows(economics: PeriodEconomics) {
    val deliveries = economics.totals.deliveries
    val netPerDrop = if (deliveries > 0) economics.netProfit / deliveries else null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RateRow(stringResource(R.string.money_tab_stat_net_per_hour), economics.netPerHour)
        RateRow(stringResource(R.string.money_tab_stat_net_per_mile), economics.netPerMile)
        RateRow(stringResource(R.string.money_tab_stat_net_per_drop), netPerDrop)
    }
}

@Composable
private fun RateRow(label: String, value: Double?) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = c.text2,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value?.let { Formats.money(it) } ?: EMPTY_VALUE,
            style = AppTheme.num.smNum,
            color = c.text,
        )
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
 * Platform split (#973 / brief §4.2, `2a`) — one **hairline row** per platform with any record in the
 * window (#1024 B4): badge, a proportional kept bar, the kept figure, and the delivery count.
 *
 * The row used to be a two-column block of four stacked figures (gross over "N deliveries", kept
 * under gross). Gross went: the money card's headline states the window's gross and its came-in bar
 * decomposes it, so a per-platform gross column was a second decomposition of the same total with no
 * bar to make it readable. What is left is the comparison the card exists for — who paid more — and
 * the bar is what actually answers it at a glance, scaled to the window's best platform via the same
 * `PatternsModel.netBarFraction` + shared [NetBar] the store leaderboard uses (Principle 5 — one
 * bar-scale rule, one bar render).
 *
 * **The bar gets its own full-width line** (review F4). Inline after the badge, its track began after
 * a variable-width chip and ended before a variable-width dollar figure, so two rows with the same
 * fraction drew visibly different bars — a comparison graphic that lies about the comparison. On its
 * own line every row's track is the card's width, which makes the fractions comparable by
 * construction rather than by luck of the label lengths (the leaderboard's shape, for the same
 * reason).
 *
 * Every label comes from the [Platform] registry's own display metadata, and the rows themselves come
 * from the DATA (whatever wires the window's records carry), so this card needs no edit when a third
 * platform ships — Principle 8. Hidden entirely below two platforms: a one-row "split" of a
 * single-platform window is noise, and the totals above already say it.
 */
@Composable
fun PlatformSplitCard(rows: List<PlatformEconomics>, modifier: Modifier = Modifier) {
    if (rows.size < 2) return
    val c = AppTheme.colors
    val maxNet = rows.maxOf { it.economics.netProfit }
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_platform_split_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        rows.forEachIndexed { index, row ->
            if (index > 0) HairlineDivider()
            PlatformSplitRow(row, maxNet)
        }
    }
}

@Composable
private fun PlatformSplitRow(row: PlatformEconomics, maxNet: Double) {
    val c = AppTheme.colors
    val net = row.economics.netProfit
    val netColor = if (net >= 0.0) c.good else c.bad
    val deliveries = row.economics.totals.deliveries
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // Registry-resolved label — never a literal (Principle 8).
            AppChip(text = row.platform.shortName.ifEmpty { row.platform.displayName })
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${Formats.commaInt(deliveries)} " +
                    if (deliveries == 1) {
                        stringResource(R.string.time_tab_delivery_singular)
                    } else {
                        stringResource(R.string.time_tab_delivery_plural)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.money_tab_platform_split_kept_format, Formats.money(net)),
                style = AppTheme.num.smNum,
                color = netColor,
            )
        }
        Spacer(Modifier.height(6.dp))
        // Full-width by construction, so every row's track is the same length and the fractions
        // are actually comparable (review F4).
        NetBar(fraction = PatternsModel.netBarFraction(net, maxNet), color = netColor)
    }
}

/*
 * `TopStoresCard` lived here until #1024 B5, when the store leaderboard became the Playbook's
 * "Where you earn" (`ui/main/playbook/WhereYouEarnCard.kt`, moved in part 1 of the same issue).
 * There is exactly ONE store list in the app now: a window-scoped top-5 on Money and a lifetime
 * leaderboard on the Playbook answered overlapping questions with different scopes and different
 * row vocabularies, which is the drift Principle 5 punishes. Its `perStoreEconomics` read left the
 * hub's ViewModel with it — the Playbook reads `storeReportCards()`, its own source.
 */

/**
 * Recent dashes, newest first, as a **hairline row list** (#1024 B6 — the rows are divider-separated
 * rather than floating in whitespace; the content is untouched). Sessions don't carry a frozen net, so
 * the money column shows the platform-reported earnings (an em dash until a summary is seen), with a
 * small "+cash" line below it when the dash has driver-entered cash tips (#688 F7) — every sibling
 * gross surface (the recap hero, the per-day chart, the drill-down) is cash-inclusive, so this keeps
 * the row from showing a different gross one tap away. Cash is shown ADDITIVELY, never folded into the
 * reported number (§9). Each row taps through to the read-only per-dash drill-down
 * ([onOpenSession], #650).
 */
@Composable
fun RecentDashesCard(
    sessions: List<SessionRecord>,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_recent_sessions_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        if (sessions.isEmpty()) {
            EmptyRow(stringResource(R.string.money_tab_no_sessions_recorded_yet))
        } else {
            sessions.forEachIndexed { index, session ->
                if (index > 0) HairlineDivider()
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
                                if (session.deliveries == 1) stringResource(R.string.time_tab_delivery_singular)
                                else stringResource(R.string.time_tab_delivery_plural),
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
                                text = stringResource(R.string.money_tab_cash_marker_format, Formats.money(session.cashTips)),
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
