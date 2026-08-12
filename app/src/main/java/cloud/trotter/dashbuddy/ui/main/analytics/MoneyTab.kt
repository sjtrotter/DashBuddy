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
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.ui.components.HowNumbersWorkFooter

/**
 * Money tab (#315 H1, reworked by #973 — redesign stage 2 of epic #969, brief §4; **decluttered from
 * nine containers to five by #1024 section B**).
 *
 * Reading order, top→bottom, one container each:
 *  1. **the money story** — `$X came in. $Y went to the car.` over two bars: what came in (base /
 *     tips / bonuses) and where it went (kept / gas / wear). Formerly two cards (#1024 B1).
 *  2. **the rates and the days they came from** — net per hour / mile / drop as inline rows above the
 *     earnings-by-day chart. Formerly a tile grid plus a chart card (#1024 B3).
 *  3. **by platform** — one hairline row per platform (#1024 B4). Hidden below two platforms.
 *  4. **needs a look** — the consolidated review rows. Hidden when the window is clean.
 *  5. **recent sessions** — tap through to the per-dash drill-down.
 *
 * Then the shared **"How these numbers work"** footer: ONE disclosure affordance for the screen
 * (#1024 rule 2), owning the frozen-cost / estimate / cash-tip wording that used to be reprinted as a
 * footnote per card. It is a footer row, not a sixth container.
 *
 * What is deliberately NOT here: the window's kept figure and its measured denominators (the recap
 * hero above the tabs owns them), and the store list (the Playbook's leaderboard owns it since #1024
 * part 1). Gross appears exactly once, as the money story's first clause.
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
    recentSessions: List<SessionRecord>,
    dailyEarnings: List<DailyEarnings>,
    orphanOfferGroups: List<OrphanOfferGroup>,
    onOpenSession: (String) -> Unit,
    onOpenNoSession: () -> Unit,
    onOpenOrphanOffers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MoneyWentCard(economics, payMix)
        // The rates render for every window; only the day chart inside is hidden for a single-day /
        // unbounded / over-long window (the repository returns an empty axis for those).
        EarningsByDayCard(economics, dailyEarnings)
        PlatformSplitCard(platformSplit)
        NeedsALookCard(
            reviewItems(
                economics = economics,
                orphanOfferGroups = orphanOfferGroups,
                onOpenNoSession = onOpenNoSession,
                onOpenOrphanOffers = onOpenOrphanOffers,
            ),
        )
        RecentDashesCard(recentSessions, onOpenSession)
        HowNumbersWorkFooter()
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
