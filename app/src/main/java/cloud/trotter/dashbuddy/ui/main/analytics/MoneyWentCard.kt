package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
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
 * "Where your money went" (#973 / brief §4.1) — the card that replaced the true-net waterfall, whose
 * four bars were uninformative by construction (fuel and non-fuel are always a thin sliver against
 * gross, so three of the four bars carried no shape).
 *
 * Copy rules from the brief, held exactly: plain words (`gas`, `wear on the car`, `stayed with you` —
 * never "non-fuel operating cost"); no cents-per-dollar math on the default view; **never** a
 * percentage in the headline. The legend carries real dollars, not shares.
 *
 * Honesty (§9): the kept figure is labelled as **frozen** at the costs each offer was accepted under,
 * and the disclosure line names the arithmetic behind the car-cost figure (miles × the frozen rate)
 * rather than presenting it as an opaque deduction. Expanding it shows the per-mile split, but only
 * when the frozen split actually covers the window — see [MoneyWentModel].
 */
@Composable
fun MoneyWentCard(economics: PeriodEconomics, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val split = MoneyWentModel.from(economics)
    var expanded by remember { mutableStateOf(false) }

    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.money_tab_where_went_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        // The three plain-words lines — the whole point of the card.
        Text(
            text = stringResource(R.string.money_tab_where_went_came_in, Formats.money(split.cameIn)),
            style = MaterialTheme.typography.bodyLarge,
            color = c.text,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.money_tab_where_went_car, Formats.money(split.carCosts)),
            style = MaterialTheme.typography.bodyLarge,
            color = c.text2,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.money_tab_where_went_kept, Formats.money(split.stayedWithYou)),
            style = MaterialTheme.typography.bodyLarge,
            color = if (split.stayedWithYou >= 0.0) c.good else c.bad,
        )

        Spacer(Modifier.height(12.dp))
        val segments = moneyWentSegments(split)
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)

        Spacer(Modifier.height(12.dp))
        // Collapsed disclosure: the car-cost arithmetic, stated. Tapping expands to the per-mile split
        // (when the frozen split covers the window) — the detail belongs behind a tap, not in the
        // default view.
        DisclosureRow(
            text = disclosureText(split),
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            ExpandedDisclosure(split)
        }
    }
}

/**
 * kept / gas / wear when the frozen split covers the window, else kept / car costs — the
 * coverage-degrade the retired waterfall expressed as 4-step → 3-step.
 *
 * A negative derived cost (#662-F1) contributes a zero-width segment: [AppStackBar] weights on the
 * value, and a negative weight is not renderable. The signed dollar figure still appears verbatim in
 * the three lines above, so the anomaly is visible rather than papered over.
 */
@Composable
private fun moneyWentSegments(split: MoneyWentModel.Split): List<AppSegment> {
    val c = AppTheme.colors
    // F3 correction (post-#1019-review): the legend keeps its real dollar notes. An earlier pass
    // blanked them to remove the "duplicated row" F3 named, but AppLegend falls back to rendering
    // a PERCENTAGE SHARE whenever a segment's note is null/blank — which this card's own doc
    // forbids ("the legend carries real dollars, not shares") and which the untouched 2-segment
    // fallback branch below (real Formats.money note on car costs) would then have contradicted.
    // A duplicated dollar figure is the honest cost of keeping the legend's promise.
    val kept = AppSegment(
        label = stringResource(R.string.money_tab_where_went_segment_kept),
        value = split.stayedWithYou.toFloat().coerceAtLeast(0f),
        color = c.good,
        note = Formats.money(split.stayedWithYou),
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
                note = Formats.money(gas),
            ),
            AppSegment(
                label = stringResource(R.string.money_tab_where_went_segment_wear),
                value = wear.toFloat().coerceAtLeast(0f),
                color = c.neutral,
                note = Formats.money(wear),
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
            ),
        )
    }
}

/** `Car costs = 208.6 mi driven × $0.56/mi, frozen when you accepted each offer` — or, with no miles
 *  logged, the honest short form that claims no rate. */
@Composable
private fun disclosureText(split: MoneyWentModel.Split): String {
    val rate = split.ratePerMile
        ?: return stringResource(R.string.money_tab_where_went_disclosure_no_miles)
    return stringResource(
        R.string.money_tab_where_went_disclosure_format,
        Formats.decimal(split.miles),
        Formats.money(rate),
    )
}

@Composable
private fun ExpandedDisclosure(split: MoneyWentModel.Split) {
    val c = AppTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (split.hasSplit) {
            PerMileRow(stringResource(R.string.money_tab_where_went_segment_gas), split.gasPerMile)
            PerMileRow(stringResource(R.string.money_tab_where_went_segment_wear), split.wearPerMile)
        } else {
            // The frozen split doesn't cover this window (mixed frozen/fallback rows, or none at all),
            // so there is no per-mile gas/wear to show. Say so — inventing a split from the blended
            // rate would be exactly the fabrication the #659 guard exists to prevent.
            Text(
                text = stringResource(R.string.money_tab_where_went_no_split),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
        Text(
            text = stringResource(R.string.money_tab_where_went_frozen_note),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

@Composable
private fun PerMileRow(label: String, perMile: Double?) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = c.text2, modifier = Modifier.weight(1f))
        Text(
            text = perMile?.let { stringResource(R.string.money_tab_per_mile_format, Formats.money3(it)) } ?: EMPTY_VALUE,
            style = AppTheme.num.smNum,
            color = c.text,
            textAlign = TextAlign.End,
        )
    }
}
