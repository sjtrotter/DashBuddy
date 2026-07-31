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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppHeatScale
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.domain.format.hourOfDayLabel
import cloud.trotter.dashbuddy.ui.main.analytics.PatternsModel.HeatmapMode
import cloud.trotter.dashbuddy.ui.main.analytics.PatternsModel.LeaderboardSort
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
        AllTimeBadge()
        HeatmapCard(heatmap)
        StoresCard(storeCards)
    }
}

/**
 * #979: declares this tab as always-lifetime — it ignores the #970 `‹ ›` pager above it by design
 * (rate/pattern surfaces need the driver's whole history, not one window), which otherwise reads as a
 * bug the first time a driver pages the header and nothing here moves.
 */
@Composable
private fun AllTimeBadge() {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AppChip(text = stringResource(R.string.patterns_tab_all_time_badge))
        Text(
            text = stringResource(R.string.patterns_tab_all_time_caption),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Heatmap ───────────────────────────────────────────────────────────

/**
 * The hour×day heatmap: 7 day-rows × 24 hour-columns. #979 adds a Rate/Hours toggle over ONE grid —
 * both values are already computed per cell ([EarningsHeatmapCell.dollarsPerHour] /
 * [EarningsHeatmapCell.coverageHours]) and, pre-#979, only Rate was ever rendered (brief §6).
 *
 * **Rate** (unchanged): tinted by the driver's own realized net $/hr for that hour-of-week. A cell
 * below the coverage floor renders as *insufficient* (near-empty), visually distinct from a
 * genuinely-zero cell (worked, earned ~nothing) — the third state gets a `bad` border on its `badBg`
 * fill. The ramp is scaled to the driver's own best hour, so it reads as "your best/worst times", never
 * an absolute-dollar claim.
 *
 * **Hours** (new): tinted by how many hours of coverage that cell has, scaled to the driver's own
 * most-covered hour-of-week. There is no "worked, no net" state here — coverage has no bad outcome,
 * only more or less of it — so a genuinely-zero-coverage cell folds into the SAME "no data" swatch as
 * an insufficient Rate cell ([PatternsModel.cellValue] does this on purpose, reusing [AppHeatScale]
 * unchanged rather than adding a second color path).
 */
@Composable
private fun HeatmapCard(heatmap: EarningsHeatmap) {
    val c = AppTheme.colors
    var mode by rememberSaveable { mutableStateOf(HeatmapMode.RATE) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(if (mode == HeatmapMode.RATE) R.string.patterns_tab_heatmap_title else R.string.patterns_tab_heatmap_title_hours),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
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

        val modeOptions = heatmapModeOptions()
        val selectedModeLabel = modeOptions.first { it.mode == mode }.label
        AppSegmented(
            options = modeOptions.map { it.label },
            selected = selectedModeLabel,
            onSelect = { label -> modeOptions.firstOrNull { it.label == label }?.let { mode = it.mode } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))

        val maxRate = heatmap.maxDollarsPerHour ?: 0.0
        val maxHours = PatternsModel.maxCoverageHours(heatmap)
        val maxForMode = if (mode == HeatmapMode.RATE) maxRate else maxHours

        // Grid + axis: the shared render the Weekly Plan screen also draws (#981), so the two
        // surfaces can never disagree about a colour, a mask or the day order.
        HeatmapGrid(heatmap = heatmap, mode = mode, maxForMode = maxForMode)
        Spacer(Modifier.height(6.dp))
        HeatmapHourAxis()
        Spacer(Modifier.height(12.dp))
        if (mode == HeatmapMode.RATE) HeatmapLegend(maxRate) else HeatmapHoursLegend()

        // Best-hour callout is a Rate concept (the single most-earning cell) — showing a $/hr figure
        // while the grid is tinted by coverage would read as mismatched, so it's Rate-mode only.
        if (mode == HeatmapMode.RATE) {
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
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(if (mode == HeatmapMode.RATE) R.string.patterns_tab_heatmap_caption else R.string.patterns_tab_heatmap_caption_hours),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/** The heatmap Rate/Hours segments paired with their resolved label (#428 Half A) — selection stays
 *  keyed off the enum, never the resolved string. */
private data class HeatmapModeOption(val mode: HeatmapMode, val label: String)

@Composable
private fun heatmapModeOptions(): List<HeatmapModeOption> = listOf(
    HeatmapModeOption(HeatmapMode.RATE, stringResource(R.string.patterns_tab_heatmap_mode_rate)),
    HeatmapModeOption(HeatmapMode.HOURS, stringResource(R.string.patterns_tab_heatmap_mode_hours)),
)

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
        listOf(0.0, 0.5, 1.0).forEach { f -> LegendSwatch(AppHeatScale.ramp(f.toFloat(), c)) }
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_high), style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}

/**
 * The Hours-mode legend (#979): just the "no data" swatch (never dashed this hour) + the low→high
 * coverage ramp — no covered-but-bad third state, since coverage has no bad outcome (see [HeatmapCard]
 * KDoc).
 */
@Composable
private fun HeatmapHoursLegend() {
    val c = AppTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendSwatch(c.surface3)
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_hours_none), style = MaterialTheme.typography.labelSmall, color = c.text3)
        Spacer(Modifier.width(8.dp))
        Text(text = stringResource(R.string.patterns_tab_heatmap_legend_low), style = MaterialTheme.typography.labelSmall, color = c.text3)
        listOf(0.0, 0.5, 1.0).forEach { f -> LegendSwatch(AppHeatScale.ramp(f.toFloat(), c)) }
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

// ── Store report cards ──────────────────────────────────────────────────

/**
 * The store **leaderboard** (#979, replacing the old stacked cards): one row per store — rank, name,
 * area chip, a proportional net bar, `N deliveries · usual wait` inline, net figure, chevron. Sort
 * chips (By net / By wait / Recent) reorder the rows via [PatternsModel.sortedStores]; the net bar's
 * scale ([PatternsModel.maxNet]) and the outlier fleet ([PatternsModel.isWaitOutlier]) are both computed
 * over the FULL [cards] list regardless of sort, so switching chips reorders rows without rescaling or
 * re-flagging them underneath the driver. Tapping a row opens [StoreDetailSheet] with the full detail
 * (pickups, gross, the dwell distribution with its precise stat terms, first/last seen). Selection is a
 * local [rememberSaveable] `selectedStoreKey` (UDF — state down, the tap event up); the sheet body
 * re-derives from the same [cards] list, so a projector re-emit keeps it fresh with no extra state
 * (a selection whose store leaves the list is explicitly cleared).
 */
@Composable
private fun StoresCard(cards: List<StoreReportCard>) {
    val c = AppTheme.colors
    var selectedStoreKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LeaderboardSort.NET) }

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
            val sortOptions = leaderboardSortOptions()
            val selectedSortLabel = sortOptions.first { it.sort == sort }.label
            AppSegmented(
                options = sortOptions.map { it.label },
                selected = selectedSortLabel,
                onSelect = { label -> sortOptions.firstOrNull { it.label == label }?.let { sort = it.sort } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            val maxNet = PatternsModel.maxNet(cards)
            val ranked = PatternsModel.sortedStores(cards, sort)
            ranked.forEachIndexed { index, card ->
                if (index > 0) Spacer(Modifier.height(14.dp))
                LeaderboardRow(
                    rank = index + 1,
                    card = card,
                    maxNet = maxNet,
                    outlier = PatternsModel.isWaitOutlier(cards, card),
                    onClick = { selectedStoreKey = card.storeKey },
                )
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

/** The leaderboard sort chips paired with their resolved label (#428 Half A) — selection stays keyed
 *  off the enum, never the resolved string. */
private data class LeaderboardSortOption(val sort: LeaderboardSort, val label: String)

@Composable
private fun leaderboardSortOptions(): List<LeaderboardSortOption> = listOf(
    LeaderboardSortOption(LeaderboardSort.NET, stringResource(R.string.patterns_tab_leaderboard_sort_net)),
    LeaderboardSortOption(LeaderboardSort.WAIT, stringResource(R.string.patterns_tab_leaderboard_sort_wait)),
    LeaderboardSortOption(LeaderboardSort.RECENT, stringResource(R.string.patterns_tab_leaderboard_sort_recent)),
)

/**
 * One leaderboard row (#979): rank digit, store name + area chip, a proportional net bar scaled to
 * [maxNet] (the #1 store), `N deliveries · usual wait` inline (the wait figure ambers when [outlier] —
 * see [PatternsModel.isWaitOutlier]), the net figure, and the existing tap-through chevron. Tapping
 * opens [StoreDetailSheet] with the full stat-labeled detail — same drill-down as before #979, just a
 * denser face.
 */
@Composable
private fun LeaderboardRow(rank: Int, card: StoreReportCard, maxNet: Double, outlier: Boolean, onClick: () -> Unit) {
    val c = AppTheme.colors
    val detailsLabel = stringResource(R.string.patterns_tab_store_details_cd, card.chainDisplay)
    val netColor = if (card.net >= 0.0) c.good else c.bad
    val fraction = PatternsModel.netBarFraction(card.net, maxNet)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = detailsLabel, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.patterns_tab_leaderboard_rank_format, rank),
            style = AppTheme.num.smNum,
            color = c.text3,
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = card.chainDisplay,
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.text,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                StoreLocationChip(card)
            }
            Spacer(Modifier.height(6.dp))
            // Proportional net bar — fraction-of-#1-store, so it stays a stable scale across sort chips.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(c.surface3),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(netColor),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val deliveriesWord = if (card.deliveries == 1) {
                stringResource(R.string.time_tab_delivery_singular)
            } else {
                stringResource(R.string.time_tab_delivery_plural)
            }
            Row {
                Text(
                    text = "${Formats.commaInt(card.deliveries)} $deliveriesWord · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.text3,
                )
                Text(
                    text = card.p50DwellMillis?.let { "${formatDuration(it)} ${stringResource(R.string.patterns_tab_leaderboard_wait_suffix)}" }
                        ?: stringResource(R.string.patterns_tab_leaderboard_no_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outlier) c.warn else c.text3,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = Formats.money(card.net),
            style = AppTheme.num.smNum,
            color = netColor,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = c.text3,
            modifier = Modifier.size(20.dp),
        )
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
