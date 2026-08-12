package cloud.trotter.dashbuddy.ui.main.playbook

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
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
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import cloud.trotter.dashbuddy.domain.format.formatShortDate
import cloud.trotter.dashbuddy.ui.components.PatternsModel
import cloud.trotter.dashbuddy.ui.components.PatternsModel.LeaderboardSort

/**
 * **Where you earn** (#1024 section C) — the store leaderboard, moved here from the retired Patterns
 * tab (#159 / #979). One row per store: rank, name, area chip, a proportional net bar,
 * `N deliveries · usual wait` inline, the net figure, and the tap-through chevron.
 *
 * It is the **only** store list in the app once #1024's part 2 lands: the Money tab's `TopStoresCard`
 * showed a period-scoped top-5 of the same entity, and two lists of the same stores under two different
 * scopes is exactly the repetition this issue exists to remove. This one is lifetime-scoped, which is
 * the scope the question "where should I go?" actually has.
 *
 * Sort chips (By net / By wait / Recent) reorder via [PatternsModel.sortedStores]; the bar's scale
 * ([PatternsModel.maxNet]) and the outlier fleet ([PatternsModel.isWaitOutlier]) are computed over the
 * FULL list regardless of sort, so switching chips reorders rows without rescaling or re-flagging them
 * underneath the driver. Selection is a local [rememberSaveable] key (UDF — state down, tap up) and the
 * sheet re-derives from the same list, so a projector re-emit keeps it fresh.
 *
 * Copy keeps its `patterns_tab_*` string ids — same words, and the id is the copy's identity.
 */
@Composable
fun WhereYouEarnCard(cards: List<StoreReportCard>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var selectedStoreKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LeaderboardSort.NET) }

    // If the selected store leaves the list (e.g. a projector refold re-keys it), clear the selection
    // explicitly — a stale key lingering in rememberSaveable could silently re-open the sheet if that
    // key ever reappeared.
    LaunchedEffect(cards, selectedStoreKey) {
        if (selectedStoreKey != null && cards.none { it.storeKey == selectedStoreKey }) {
            selectedStoreKey = null
        }
    }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.patterns_tab_stores_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(4.dp))
        // v1 asymmetry: manually-added + unresolved deliveries are in the Money totals but don't
        // surface as store rows here yet. State it plainly so the lists don't look inconsistent.
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
    // a vanished store is explicitly cleared above. Dismiss = swipe/scrim (M3 default).
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
 * [maxNet] (the #1 store), `N deliveries · usual wait` inline (the wait figure ambers when [outlier]),
 * the net figure, and the tap-through chevron.
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
 * ("Usual wait (median)", "Longest waits (p95)") — and the visited-range line. Pure data in, one
 * `onDismiss` lambda out (UDF).
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
 *  else a "location unknown" warn chip. Shared by the row face and the detail sheet. */
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

/** A compact label-over-value stat cell for the detail sheet's totals grid. */
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
