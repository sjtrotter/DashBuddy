package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * Money tab (#315 H1, reworked by #973 — redesign stage 2 of epic #969, brief §4).
 *
 * Reading order, top→bottom: **where your money went** (§4.1, the card that replaced the true-net
 * waterfall) → **what made up the gross** (§4.2 pay mix) → the three rate tiles → earnings by day
 * (tappable) → the platform split → **needs a look** (the consolidated review card) → top stores →
 * recent dashes. The window itself is chosen by the pager above the tabs (#970), so nothing here owns
 * a period selector.
 *
 * The gross headline the tab used to lead with is gone: the recap hero above states net + gross for
 * the same window, and §4.1's first line is literally "$X came in" — three copies of one number on
 * one screen is noise, not reassurance.
 *
 * Pure data in / lambdas out — no side effects, no clock (every figure is a settled historical value).
 * Every rendered string routes through the [Formats]/`TimeFormats` SSOT, and all economics are
 * frozen-net (an economy edit never rewrites a past window).
 */
@Composable
fun MoneyTab(
    economics: PeriodEconomics,
    payMix: PayMix,
    platformSplit: List<PlatformEconomics>,
    topStores: List<StoreEconomics>,
    recentSessions: List<SessionRecord>,
    dailyEarnings: List<DailyEarnings>,
    orphanOfferGroups: List<OrphanOfferGroup>,
    onOpenSession: (String) -> Unit,
    onOpenNoSession: () -> Unit,
    onOpenOrphanOffers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MoneyWentCard(economics)
        PayMixCard(payMix)
        RateTiles(economics)
        // Hidden for a single-day / unbounded / over-long window — the repository returns an empty axis.
        if (dailyEarnings.isNotEmpty()) EarningsByDayCard(dailyEarnings)
        PlatformSplitCard(platformSplit)
        NeedsALookCard(
            reviewItems(
                economics = economics,
                orphanOfferGroups = orphanOfferGroups,
                onOpenNoSession = onOpenNoSession,
                onOpenOrphanOffers = onOpenOrphanOffers,
            ),
        )
        TopStoresCard(topStores)
        RecentDashesCard(recentSessions, onOpenSession)
    }
}

/**
 * Per hour / per mile / per drop as three equal tiles (#973 / brief §4.2), with miles + deliveries
 * beneath them (the two measured denominators — they appear nowhere else on this tab).
 *
 * Every rate is **kept** money over a measured denominator, and each stays `null` — rendered as the
 * em-dash placeholder — until its denominator exists: the [NetProfit] discipline, so a window with no
 * logged miles shows "—" rather than a fabricated `$0.00/mi`. Per-drop uses the same rule, computed
 * here against the delivery count because it is the one rate the read model does not already carry.
 */
@Composable
private fun RateTiles(economics: PeriodEconomics) {
    val deliveries = economics.totals.deliveries
    val netPerDrop = if (deliveries > 0) economics.netProfit / deliveries else null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = stringResource(R.string.money_tab_stat_net_per_hour),
                value = economics.netPerHour?.let { Formats.money(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.money_tab_stat_net_per_mile),
                value = economics.netPerMile?.let { Formats.money(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.money_tab_stat_net_per_drop),
                value = netPerDrop?.let { Formats.money(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = stringResource(R.string.money_tab_stat_miles),
                value = Formats.decimal(economics.totals.miles),
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.money_tab_stat_deliveries),
                value = Formats.commaInt(deliveries),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The analytics hub's shared "nothing here yet" line — ONE owner (#973). Money, Decisions, Time and
 * the per-dash drill-down each carried a byte-identical `private` copy of this; the tab split made the
 * duplication load-bearing, so it is consolidated here rather than grown to five copies (Principle 5).
 */
@Composable
internal fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text3,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
