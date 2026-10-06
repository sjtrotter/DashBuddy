package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord

/**
 * Simple Money view: existing economics as tiles, then raised review flags. More reveals the
 * cost/pay charts, per-mile and per-drop rates, daily/platform breakdowns and latest dashes.
 * [AnalyticsScreen] owns the shared toggle and the screen's single explanation footer.
 * Data in / actions out only; the existing read-model and interpretation owners supply every figure.
 */
@Composable
fun MoneyTab(
    economics: PeriodEconomics,
    showMore: Boolean,
    payMix: PayMix,
    platformSplit: List<PlatformEconomics>,
    recentSessions: List<SessionRecord>,
    dailyEarnings: List<DailyEarnings>,
    orphanOfferGroups: List<OrphanOfferGroup>,
    onOpenSession: (String) -> Unit,
    onOpenNoSession: () -> Unit,
    onOpenOrphanOffers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TierOneFigures(moneyFigures(economics, payMix))
        NeedsALookCard(
            reviewItems(
                economics = economics,
                orphanOfferGroups = orphanOfferGroups,
                onOpenNoSession = onOpenNoSession,
                onOpenOrphanOffers = onOpenOrphanOffers,
            ),
        )
        if (showMore) {
            MoneyWentCard(economics, payMix)
            EarningsByDayCard(economics, dailyEarnings)
            PlatformSplitCard(platformSplit)
            RecentDashesCard(recentSessions, onOpenSession)
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
