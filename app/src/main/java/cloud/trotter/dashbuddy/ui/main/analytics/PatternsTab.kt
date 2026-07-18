package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.theme.AppColors
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCell
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.format.hourOfDayLabel
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Patterns tab (#315 H5): the driver's own **lifetime** patterns — top→bottom, the net-$/hr
 * hour×day heatmap ("when your time actually pays") and the per-store report cards ("where you go",
 * #159). Pure data in ([EarningsHeatmap] + [StoreReportCard] list), no side effects (Principle 1 — UDF);
 * lifetime-scoped, so the hub renders it with **no** period selector.
 *
 * **Framing discipline (Pledge-adjacent):** every figure is the driver's *own realized* net $/hr and
 * *own* dwell — empirical measurement of their own experience, never a platform-pay characterization
 * ("DoorDash pays more at…" is out of bounds; "you earn more at…" is in). Store/merchant names are
 * merchant data — fine to render (Principle 7 governs logs, not this UI); customer PII is sha256'd
 * upstream and never reaches here (Principle 6). No network, no new capture surface.
 *
 * Aggregate-historical, not a live surface — no `rememberNow()` ticker (Reactive UI: nothing here can
 * go stale while looked at; the read-model Flows behind the ViewModel re-emit on projector commits).
 */
@Composable
fun PatternsTab(
    storeCards: List<StoreReportCard>,
    heatmap: EarningsHeatmap,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HeatmapCard(heatmap)
        StoresCard(storeCards)
    }
}

// ── Heatmap ─────────────────────────────────────────────────────────────

/** Local day order: Monday-first, matching the app's Monday-anchored week (#655 / PeriodBounds). */
private val DAY_ROWS = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

/**
 * The net-$/hr hour×day heatmap: 7 day-rows × 24 hour-columns, each cell tinted by the driver's own
 * realized net $/hr for that hour-of-week. A cell below the coverage floor renders as
 * *insufficient* (near-empty), visually distinct from a genuinely-zero cell (worked, earned ~nothing).
 * The color ramp is scaled to the driver's own best hour, so it reads as "your best/worst times",
 * never an absolute-dollar claim.
 */
@Composable
private fun HeatmapCard(heatmap: EarningsHeatmap) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.patterns_tab_heatmap_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))

        if (!heatmap.hasData) {
            Text(
                text = stringResource(R.string.patterns_tab_heatmap_no_data),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text3,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@AppCard
        }

        val maxRate = heatmap.maxDollarsPerHour ?: 0.0

        // Grid: each day is a row of a fixed-width label + 24 weighted cells.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            DAY_ROWS.forEach { day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.text3,
                        modifier = Modifier.width(30.dp),
                    )
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        for (hour in 0 until EarningsHeatmap.HOURS) {
                            val cell = heatmap.cell(day.value - 1, hour)
                            val rate = cell.dollarsPerHour
                            // A covered-but-≤$0 cell ("worked, no net") gets a `bad` border on its badBg
                            // fill so it reads as a distinct third state, not a dim tint (legible in both
                            // themes with the fixed palette).
                            val zeroRate = rate != null && rate <= 0.0
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cellColor(cell, maxRate, c))
                                    .then(
                                        if (zeroRate) Modifier.border(1.dp, c.bad, RoundedCornerShape(2.dp))
                                        else Modifier,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        HourAxis()
        Spacer(Modifier.height(12.dp))
        HeatmapLegend(maxRate)

        // Best-hour callout — the single most-earning cell (driver's own experience). Reads the domain
        // SSOT [EarningsHeatmap.bestCell], the same cell the color ramp is scaled to (Principle 5).
        heatmap.bestCell?.let { best ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.patterns_tab_heatmap_best_format,
                    "${DayOfWeek.of(best.dayIndex + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${hourOfDayLabel(best.hour)}",
                    Formats.money(best.dollarsPerHour!!),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.patterns_tab_heatmap_caption),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/** Four evenly-spaced clock ticks under the grid (offset past the day-label gutter). */
@Composable
private fun HourAxis() {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(30.dp))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            // labelSmall, no raw fontSize override — matches the sibling AppBarChart axis-label treatment.
            listOf(0, 6, 12, 18).forEach { h ->
                Text(text = hourOfDayLabel(h), style = MaterialTheme.typography.labelSmall, color = c.text3)
            }
            Text(text = hourOfDayLabel(0), style = MaterialTheme.typography.labelSmall, color = c.text3)
        }
    }
}

/**
 * Color-scale legend, three states so the grid is readable: the *insufficient* swatch ("too little
 * time"), the covered-but-≤$0 swatch ("worked, no net" — badBg + a `bad` border, matching the grid),
 * and the low→high positive ramp keyed to the driver's own best hour.
 */
@Composable
private fun HeatmapLegend(maxRate: Double) {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendSwatch(c.surface3)
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_insufficient), style = MaterialTheme.typography.labelSmall, color = c.text3)
        Spacer(Modifier.width(8.dp))
        LegendSwatch(c.badBg, border = c.bad)
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_zero), style = MaterialTheme.typography.labelSmall, color = c.text3)
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_low), style = MaterialTheme.typography.labelSmall, color = c.text3)
        listOf(0.0, 0.5, 1.0).forEach { f -> LegendSwatch(positiveRamp(f.toFloat(), c)) }
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_high), style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}

@Composable
private fun LegendSwatch(color: Color, border: Color? = null) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
            .then(if (border != null) Modifier.border(1.dp, border, RoundedCornerShape(2.dp)) else Modifier),
    )
}

/**
 * The tint for one cell (pure — takes the palette in). Insufficient coverage ([EarningsHeatmapCell.dollarsPerHour]
 * null) → a dim `surface3` (reads as "no data"); a rate ≤ 0 with enough coverage → the cold `badBg`
 * ("worked, earned ~nothing", paired with a `bad` border at the call site); a positive rate → the
 * [positiveRamp] scaled to [maxRate]. The insufficient and genuinely-zero cells are deliberately
 * different so a masked cell never reads as a real zero.
 */
private fun cellColor(cell: EarningsHeatmapCell, maxRate: Double, c: AppColors): Color {
    val rate = cell.dollarsPerHour ?: return c.surface3
    if (rate <= 0.0) return c.badBg
    val fraction = if (maxRate > 0.0) (rate / maxRate).coerceIn(0.0, 1.0) else 1.0
    return positiveRamp(fraction.toFloat(), c)
}

/**
 * The positive-rate ramp `goodBg → good`, but starting at **0.3** of the way up rather than at the raw
 * ~12–14%-alpha `goodBg` — otherwise a low-positive cell is an indistinguishable faint tint next to the
 * `surface3`/`badBg` cells in light mode. The lowest positive rate is thus a clearly-green swatch, and
 * the best hour is solid `good`.
 */
private fun positiveRamp(fraction: Float, c: AppColors): Color =
    lerp(c.goodBg, c.good, 0.3f + 0.7f * fraction.coerceIn(0f, 1f))

// ── Store report cards ──────────────────────────────────────────────────

/**
 * The per-store report cards, newest-visited first (the repository already orders by last-seen).
 *
 * #765: the card **face** is deliberately glanceable — store name + location chip and just the three
 * decision-driving numbers (net, usual wait, deliveries) in plain language (no "median"/"p95"
 * statistical vocabulary). Tapping a card opens [StoreDetailSheet] with the full detail (pickups,
 * gross, the dwell distribution with its precise stat terms, first/last seen). Selection is a
 * local [rememberSaveable] `selectedStoreKey` (UDF — state down, the tap event up); the sheet body
 * re-derives from the same [cards] list, so a projector re-emit keeps it fresh with no extra state
 * (a selection whose store leaves the list is explicitly cleared).
 */
@Composable
private fun StoresCard(cards: List<StoreReportCard>) {
    val c = AppTheme.colors
    var selectedStoreKey by rememberSaveable { mutableStateOf<String?>(null) }

    // If the selected store leaves the list (e.g. a projector refold re-keys it), clear the
    // selection explicitly — otherwise the stale key would linger in rememberSaveable and could
    // silently re-open the sheet if that key ever reappeared.
    LaunchedEffect(cards, selectedStoreKey) {
        if (selectedStoreKey != null && cards.none { it.storeKey == selectedStoreKey }) {
            selectedStoreKey = null
        }
    }

    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.patterns_tab_stores_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(4.dp))
        // v1 asymmetry (Money vs Patterns): manually-added + unresolved deliveries are in the Money
        // totals but don't surface as store cards here yet. State it plainly so the lists don't look
        // inconsistent to the driver.
        Text(
            text = stringResource(R.string.patterns_tab_stores_manual_note),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        if (cards.isEmpty()) {
            Text(
                text = stringResource(R.string.patterns_tab_no_stores),
                style = MaterialTheme.typography.bodyMedium,
                color = c.text3,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            cards.forEachIndexed { index, card ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                StoreRow(card, onClick = { selectedStoreKey = card.storeKey })
            }
        }
    }

    // The selected store is looked up from the live list by key, so the sheet re-derives on re-emit;
    // a vanished store is explicitly cleared by the LaunchedEffect above. Dismiss = swipe/scrim (M3 default).
    selectedStoreKey?.let { key ->
        cards.firstOrNull { it.storeKey == key }?.let { card ->
            StoreDetailSheet(card = card, onDismiss = { selectedStoreKey = null })
        }
    }
}

/**
 * The glanceable card **face** (#765): store name + location chip, a tap-affordance chevron, and the
 * three decision-driving numbers in plain language — net, "usual wait", deliveries. Everything else
 * (pickups, gross, the dwell distribution, first/last seen) lives in [StoreDetailSheet] behind a tap.
 * "Usual wait" is the median dwell rendered without the statistical label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreRow(card: StoreReportCard, onClick: () -> Unit) {
    val c = AppTheme.colors
    // The row itself is the explicit button (role + onClickLabel); the chevron is decorative.
    val detailsLabel = stringResource(R.string.patterns_tab_store_details_cd, card.chainDisplay)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = detailsLabel, role = Role.Button, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.chainDisplay,
                style = MaterialTheme.typography.bodyLarge,
                color = c.text,
                modifier = Modifier.weight(1f),
            )
            StoreLocationChip(card)
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = c.text3,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StoreStat(
                stringResource(R.string.patterns_tab_store_net_label),
                Formats.money(card.net),
                valueColor = if (card.net >= 0.0) c.good else c.bad,
            )
            StoreStat(
                stringResource(R.string.patterns_tab_store_usual_wait_label),
                card.p50DwellMillis?.let { formatDuration(it) } ?: EMPTY_VALUE,
            )
            StoreStat(stringResource(R.string.patterns_tab_store_deliveries_label), Formats.commaInt(card.deliveries))
        }
    }
}

/**
 * The full-detail bottom sheet (#765) for one store: totals grid (pickups/deliveries/gross/net), the
 * dwell distribution where precision is welcome — labeled dasher-first with the stat term secondary
 * ("Usual wait (median)", "Longest waits (p95)") — and the visited-range line. Dismiss = swipe/scrim
 * (M3 [ModalBottomSheet] default). Pure data in, one `onDismiss` lambda out (UDF).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StoreDetailSheet(card: StoreReportCard, onDismiss: () -> Unit) {
    val c = AppTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.chainDisplay,
                    style = MaterialTheme.typography.titleLarge,
                    color = c.text,
                    modifier = Modifier.weight(1f),
                )
                StoreLocationChip(card)
            }
            card.address?.let {
                Text(text = it, style = MaterialTheme.typography.bodyMedium, color = c.text3)
            }

            HorizontalDivider(color = c.line)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StoreStat(stringResource(R.string.patterns_tab_store_pickups_label), Formats.commaInt(card.pickups))
                StoreStat(stringResource(R.string.patterns_tab_store_deliveries_label), Formats.commaInt(card.deliveries))
                StoreStat(stringResource(R.string.patterns_tab_store_gross_label), Formats.money(card.gross))
                StoreStat(
                    stringResource(R.string.patterns_tab_store_net_label),
                    Formats.money(card.net),
                    valueColor = if (card.net >= 0.0) c.good else c.bad,
                )
            }

            HorizontalDivider(color = c.line)

            Text(
                text = stringResource(R.string.patterns_tab_store_detail_wait_title),
                style = MaterialTheme.typography.labelMedium,
                color = c.text3,
            )
            if (card.avgDwellMillis == null) {
                Text(
                    text = stringResource(R.string.patterns_tab_store_dwell_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.text3,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DetailStatRow(
                        stringResource(R.string.patterns_tab_store_detail_median),
                        card.p50DwellMillis?.let { formatDuration(it) },
                    )
                    DetailStatRow(
                        stringResource(R.string.patterns_tab_store_detail_p95),
                        card.p95DwellMillis?.let { formatDuration(it) },
                    )
                    DetailStatRow(
                        stringResource(R.string.patterns_tab_store_detail_avg),
                        formatDuration(card.avgDwellMillis!!.toLong()),
                    )
                }
                // F6: a chain-only ("location unknown") entity blends multiple physical stores.
                if (!card.locationKnown) {
                    Text(
                        text = stringResource(R.string.patterns_tab_stats_partial, card.chainDisplay),
                        style = MaterialTheme.typography.labelSmall,
                        color = c.warn,
                    )
                }
            }

            HorizontalDivider(color = c.line)

            Text(
                text = stringResource(
                    R.string.patterns_tab_store_seen_format,
                    formatShortDate(card.firstSeenAt),
                    formatShortDate(card.lastSeenAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
    }
}

/** The store location chip: the #773 street line for an address-derived key, else the running key,
 *  else a "location unknown" warn chip. Shared by the card face and the detail sheet. */
@Composable
private fun StoreLocationChip(card: StoreReportCard) {
    val c = AppTheme.colors
    val runningKey = card.runningKey
    if (card.locationKnown && runningKey != null) {
        // #773: an address-derived key (`@12125`) is a provenance marker, not a label — show the
        // street line of the store address instead of the raw `@number`.
        val chipText = if (runningKey.startsWith("@")) {
            card.address?.substringBefore(",")?.trim()?.takeIf { it.isNotEmpty() } ?: runningKey
        } else {
            runningKey
        }
        AppChip(text = chipText, uppercase = false)
    } else {
        AppChip(
            text = stringResource(R.string.patterns_tab_location_unknown),
            color = c.warn,
            container = c.warnBg,
            uppercase = false,
        )
    }
}

/** A compact label-over-value stat cell for the store card face + sheet grid (inline, not a bordered [AppStatTile]). */
@Composable
private fun StoreStat(label: String, value: String, valueColor: Color = AppTheme.colors.text) {
    Column {
        Text(text = label.uppercase(), style = AppTheme.num.chip, color = AppTheme.colors.text3)
        Text(text = value, style = AppTheme.num.smNum, color = valueColor, textAlign = TextAlign.Start)
    }
}

/** A label ↔ value row for the detail sheet's dwell distribution (dasher-friendly label left, stat right). */
@Composable
private fun DetailStatRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppTheme.colors.text2,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: EMPTY_VALUE,
            style = AppTheme.num.smNum,
            color = AppTheme.colors.text,
        )
    }
}
