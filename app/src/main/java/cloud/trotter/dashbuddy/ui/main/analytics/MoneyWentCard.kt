package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.components.HairlineDivider
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.PayMix
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.format.Formats

/**
 * Pure decision logic for "Where your money went" (#973 / brief §4.1) — Compose-free so the coverage
 * reasoning is provable without rendering (the `NetDelta`/`WindowLabel` precedent).
 *
 * **This carries over the retired true-net waterfall's coverage guard verbatim (#659).** The frozen
 * fuel/non-fuel split is only trustworthy for a window when both sums are present AND they reconcile
 * against the window's derived operating cost (`gross − net`) within tolerance: a window that mixes
 * `OFFER_FROZEN` rows with pre-split fallback rows has the split covering only the frozen subset, so
 * the sums fall short of the real cost — that shortfall IS the coverage signal, no separate flag
 * needed. What changed is only the SHAPE the failure degrades into: the old surface dropped from a
 * 4-bar waterfall to a 3-bar one, this one drops from a 3-segment bar (kept / gas / wear) to a
 * 2-segment bar (kept / car costs). The arithmetic, the tolerance, and the "never fabricate a split"
 * rule are unchanged.
 */
object MoneyWentModel {

    /** Whichever is larger wins: a flat cent floor for small windows, 1% for large ones (#659). */
    private const val RELATIVE_TOLERANCE = 0.01
    private const val ABSOLUTE_TOLERANCE_DOLLARS = 0.50

    /**
     * The window's money, split the way §4.1 states it.
     *
     * [carCosts] is the DERIVED cost `gross − net` — the same quantity the waterfall's 3-step fallback
     * showed — which CAN go negative in the reported-under-delivered shape (#662-F1). The signed value
     * is kept honest here and the bar renders that segment at zero width rather than inverting.
     *
     * [gas]/[wear] are non-null **only** when the coverage guard passes; a null pair is the instruction
     * to render the 2-segment form. [ratePerMile] is null when no miles were logged (a rate with no
     * denominator is not a fact).
     */
    data class Split(
        val cameIn: Double,
        val carCosts: Double,
        val stayedWithYou: Double,
        val gas: Double?,
        val wear: Double?,
        val miles: Double,
        val ratePerMile: Double?,
    ) {
        /** True when the frozen fuel/non-fuel split covers the window — the 3-segment bar. */
        val hasSplit: Boolean get() = gas != null && wear != null

        /** Per-mile gas, for the expanded disclosure. Null unless [hasSplit] and miles are logged. */
        val gasPerMile: Double? get() = perMile(gas)

        /** Per-mile wear, for the expanded disclosure. Null unless [hasSplit] and miles are logged. */
        val wearPerMile: Double? get() = perMile(wear)

        private fun perMile(amount: Double?): Double? =
            if (amount != null && miles > 0.0) amount / miles else null
    }

    fun from(economics: PeriodEconomics): Split {
        val gross = economics.grossEarnings
        val net = economics.netProfit
        val cost = gross - net
        val fuel = economics.fuelCost
        val nonFuel = economics.nonFuelCost
        val covered = fuel != null && nonFuel != null &&
            kotlin.math.abs((fuel + nonFuel) - cost) <=
            maxOf(cost * RELATIVE_TOLERANCE, ABSOLUTE_TOLERANCE_DOLLARS)
        val miles = economics.totals.miles
        return Split(
            cameIn = gross,
            carCosts = cost,
            stayedWithYou = net,
            gas = fuel.takeIf { covered },
            wear = nonFuel.takeIf { covered },
            miles = miles,
            ratePerMile = if (miles > 0.0) cost / miles else null,
        )
    }
}

/**
 * More-mode money detail: Earned's pay mix and the kept/costs composition. The simple tiles own
 * earned and car-cost totals and arithmetic. Gas/wear per-mile detail stays in the legend, while
 * the screen footer owns missing-split and frozen-cost explanations.
 * A losing window keeps its signed Kept line because the chart cannot carry a negative segment.
 */
@Composable
fun MoneyWentCard(economics: PeriodEconomics, payMix: PayMix, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val split = MoneyWentModel.from(economics)

    AppCard(modifier = modifier.fillMaxWidth()) {
        // A LOSING window is the one case the third clause comes back (#1024 review F3). The bar
        // can't carry it — [AppStackBar] weights on the value and a negative weight is not
        // renderable, so a negative kept segment is a zero-width sliver — and its legend note is
        // deliberately silent, so with the clause gone too a window that cost more than it paid
        // would show a green "Stayed with you" key beside nothing at all: the anomaly papered over,
        // which is exactly what #662-F1 asked this card never to do. One duplicated figure on an
        // abnormal window is a cheaper price than a silent one, and the hero states it in the same
        // bad tone.
        if (split.stayedWithYou < 0.0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.money_tab_where_went_kept, Formats.money(split.stayedWithYou)),
                style = MaterialTheme.typography.bodyLarge,
                color = c.bad,
            )
        }

        Spacer(Modifier.height(14.dp))
        PayMixSection(payMix)

        HairlineDivider(gap = 14.dp)

        Text(
            text = stringResource(R.string.money_tab_where_went_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))
        val segments = moneyWentSegments(split)
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)

    }
}

/**
 * kept / gas / wear when the frozen split covers the window, else kept / car costs — the
 * coverage-degrade the retired waterfall expressed as 4-step → 3-step.
 *
 * A negative derived cost (#662-F1) contributes a zero-width segment: [AppStackBar] weights on the
 * value, and a negative weight is not renderable. The signed car-cost figure still appears verbatim
 * in the Car costs tile, so the anomaly is visible rather than papered over.
 *
 * **The kept segment states NOTHING in the legend (#1024 B2), and that is deliberate.** It sets
 * `noteHidden` — the design system's explicit third state (see [AppSegment]), NOT a null note, which
 * would make the legend compute a PERCENTAGE SHARE and this card's own rule forbids shares. The
 * reason the dollars go is that the kept figure is the recap hero's headline, one scroll up; the gas
 * / wear notes stay REAL dollars with their measured per-mile rates. This
 * retires the standing comment on the previous pass ("a duplicated dollar figure is the honest cost
 * of keeping the legend's promise") — a first-class "say nothing here" keeps the promise without the
 * duplicate, and `AppLegendNoteTest` pins the distinction so a future tidy-up can't collapse it.
 */
@Composable
private fun moneyWentSegments(split: MoneyWentModel.Split): List<AppSegment> {
    val c = AppTheme.colors
    val kept = AppSegment(
        label = stringResource(R.string.money_tab_where_went_segment_kept),
        value = split.stayedWithYou.toFloat().coerceAtLeast(0f),
        color = c.good,
        noteHidden = true,
    )
    val gas = split.gas
    val wear = split.wear
    return if (gas != null && wear != null) {
        listOf(
            kept,
            AppSegment(
                label = stringResource(R.string.money_tab_where_went_segment_gas),
                value = gas.toFloat().coerceAtLeast(0f),
                color = c.warn,
                note = split.gasPerMile?.let {
                    stringResource(R.string.money_tab_where_went_segment_note_format, Formats.money(gas), Formats.money3(it))
                } ?: Formats.money(gas),
            ),
            AppSegment(
                label = stringResource(R.string.money_tab_where_went_segment_wear),
                value = wear.toFloat().coerceAtLeast(0f),
                color = c.neutral,
                note = split.wearPerMile?.let {
                    stringResource(R.string.money_tab_where_went_segment_note_format, Formats.money(wear), Formats.money3(it))
                } ?: Formats.money(wear),
            ),
        )
    } else {
        listOf(
            kept,
            AppSegment(
                label = stringResource(R.string.money_tab_where_went_segment_car),
                value = split.carCosts.toFloat().coerceAtLeast(0f),
                color = c.neutral,
                note = Formats.money(split.carCosts),
                noteHidden = true,
            ),
        )
    }
}
