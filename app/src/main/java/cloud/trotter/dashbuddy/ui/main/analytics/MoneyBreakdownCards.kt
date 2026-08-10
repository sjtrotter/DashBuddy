package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.clickable
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
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.format.formatWeekdayMonthDay
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.format.TextStyle
import java.util.Locale

/**
 * Earnings-by-day (#315 H6, made **tappable** by #973 / brief §4.2): one bar per local calendar day of
 * the window, gap days at zero, the best day highlighted. Session-anchored (#655) — a dash's whole
 * gross sits on its start day; a "(No session)" delivery counts on its own completion day (the caption
 * states this). Only rendered when [days] is non-empty (a single-day, Lifetime, or over-long window
 * returns an empty axis).
 *
 * Tapping a bar selects it and prints its day, gross and delivery count below the chart — the brief's
 * "tooltip", rendered inline rather than as a floating popup so it is reachable by touch, keyboard and
 * screen reader alike, and so it never covers the bars it describes. Tapping the selected bar again
 * clears it. Selection is view-local (it survives nothing and needs to survive nothing), so it lives
 * in a `remember` here, not in the hub's UiState.
 */
@Composable
fun EarningsByDayCard(days: List<DailyEarnings>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    AppCard(modifier = modifier.fillMaxWidth()) {
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
 * Platform split (#973 / brief §4.2, `2a`) — one row per platform with any record in the window: gross,
 * kept, and the delivery count.
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
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_platform_split_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        rows.forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = row.platform.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = c.text,
                    )
                    Text(
                        text = "${Formats.commaInt(row.economics.totals.deliveries)} " +
                            if (row.economics.totals.deliveries == 1) {
                                stringResource(R.string.time_tab_delivery_singular)
                            } else {
                                stringResource(R.string.time_tab_delivery_plural)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = c.text3,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = Formats.money(row.economics.grossEarnings),
                        style = AppTheme.num.smNum,
                        color = c.text,
                    )
                    Text(
                        text = stringResource(
                            R.string.money_tab_platform_split_kept_format,
                            Formats.money(row.economics.netProfit),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.economics.netProfit >= 0.0) c.good else c.bad,
                    )
                }
            }
        }
        // F1: the frozen-net promise is stated once per screen now, by the recap hero above this
        // tab and, in full, by the "where your money went" card's own expanded disclosure — a third
        // copy here was noise on the same scroll. `money_tab_where_went_frozen_note` stays the
        // resource `MoneyWentCard` renders; this call site is the one that goes.
    }
}

/**
 * Top-earning stores for the window, each row now carrying its **platform badge** (#973 / brief §4.2)
 * so a chain worked on both platforms is readable at a glance. Store names are merchants — fine to
 * render (Principle 7 governs logs).
 *
 * The badge resolves through the [Platform] registry from the chain bucket's own key
 * (`platform|normalizedChain`, #159 F9) — no literal, and no badge at all when the row is unresolved
 * (fail-null beats guessing which platform an unkeyed row came from).
 */
@Composable
fun TopStoresCard(stores: List<StoreEconomics>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_top_stores_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        if (stores.isEmpty()) {
            EmptyRow(stringResource(R.string.money_tab_no_store_earnings_yet))
        } else {
            stores.forEachIndexed { index, store ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = store.storeName ?: stringResource(R.string.money_tab_unknown_store),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.text,
                        )
                        Text(
                            text = "${Formats.commaInt(store.deliveries)} " +
                                if (store.deliveries == 1) stringResource(R.string.time_tab_delivery_singular)
                                else stringResource(R.string.time_tab_delivery_plural),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.text3,
                        )
                    }
                    storePlatform(store)?.let { platform ->
                        AppChip(text = platform.shortName.ifEmpty { platform.displayName })
                        Spacer(Modifier.width(10.dp))
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

/** The platform behind a chain bucket key (`platform|normalizedChain`, #159 F9), or null when the row
 *  carries no key — registry-resolved, never a literal (Principle 8). */
private fun storePlatform(store: StoreEconomics): Platform? =
    store.storeKey?.substringBefore('|')?.takeIf { it.isNotEmpty() }?.let(Platform::fromWire)

/**
 * Recent dashes, newest first. Sessions don't carry a frozen net, so the money column shows the
 * platform-reported earnings (an em dash until a summary is seen), with a small "+cash" line below it
 * when the dash has driver-entered cash tips (#688 F7) — every sibling gross surface (the recap hero,
 * the per-day chart, the drill-down) is cash-inclusive, so this keeps the row from showing a different
 * gross one tap away. Cash is shown ADDITIVELY, never folded into the reported number (§9). Each row
 * taps through to the read-only per-dash drill-down ([onOpenSession], #650).
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
