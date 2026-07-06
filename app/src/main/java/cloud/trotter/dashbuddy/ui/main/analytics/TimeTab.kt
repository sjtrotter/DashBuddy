package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.export.IrsMileage
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Placeholder shown for a figure with no measurable input yet (Money/Decisions-tab parity). */
private const val EMPTY_VALUE = "—"

/**
 * Time tab (#315 H4): the measured time / mileage review for the selected period, top→bottom — the
 * online-time split (on-delivery vs unattributed), the deadhead ratio, the on-time gauge, and the
 * mileage-&-tax card. Pure data in ([TimeEconomics]), no side effects (Principle 1 — UDF).
 *
 * **Everything here is MEASURED, not estimated** — session durations, per-delivery partition deltas,
 * odometer deltas — so there's no "est." qualifier and no economy dependency (this surface reports
 * time and miles, never a re-costed value). Attribution semantics are stated honestly on each card
 * (delivery deltas include the approach legs; deadhead is the unattributed remainder; the on-time
 * rate covers only deadline-carrying deliveries). Aggregate-only: counts + durations + miles, no
 * merchant/customer text (Principle 6). Every number routes through the [Formats] / [formatDuration]
 * SSOT; the tax line reads the year + rate from [IrsMileage] (never a literal).
 */
@Composable
fun TimeTab(
    time: TimeEconomics,
    period: AnalyticsPeriod,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TimeSplitCard(time)
        DeadheadCard(time)
        OnTimeCard(time)
        MileageTaxCard(time, period)
    }
}

/** Online-time split: hero online duration + an on-delivery / unattributed stack bar + dash tiles. */
@Composable
private fun TimeSplitCard(time: TimeEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "TIME SPLIT", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (time.onlineMillis == 0L) {
            EmptyRow("No dashes in this period yet.")
            return@AppCard
        }

        Text(text = formatDuration(time.onlineMillis), style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "online across ${Formats.commaInt(time.sessions)} " +
                if (time.sessions == 1) "dash" else "dashes",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(14.dp))

        val onDeliveryMillis = time.deliveryMillis ?: 0L
        val segments = listOf(
            AppSegment("On delivery", onDeliveryMillis.toFloat(), c.good, note = formatDuration(onDeliveryMillis)),
            AppSegment("Unattributed", time.unattributedMillis.toFloat(), c.neutral, note = formatDuration(time.unattributedMillis)),
        )
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = "Dashes",
                value = Formats.commaInt(time.sessions),
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = "Avg dash",
                value = time.avgDashMillis?.let { formatDuration(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Deadhead: the share of odometer miles not attributed to any delivery. Delivery miles are odometer
 * partition deltas anchored on drop completions, so they already include the approach legs between
 * drops — the deadhead here is the honest remainder (the tail after the last drop, and dashes with
 * no delivery at all).
 */
@Composable
private fun DeadheadCard(time: TimeEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "DEADHEAD", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (time.miles <= 0.0) {
            EmptyRow("No miles measured in this period yet.")
            return@AppCard
        }

        val deadheadPct = (time.unattributedMiles / time.miles * 100.0).roundToInt()
        Text(text = "$deadheadPct%", style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            text = "miles with no delivery attached — after the last drop, or dashes with none",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(14.dp))

        // Attributed portion = total − unattributed (min of delivery-delta and total), so the bar
        // sums to the period's odometer miles cleanly.
        val onDeliveryMiles = time.miles - time.unattributedMiles
        val segments = listOf(
            AppSegment("On delivery", onDeliveryMiles.toFloat(), c.good, note = "${Formats.decimal(onDeliveryMiles, 1)} mi"),
            AppSegment("Deadhead", time.unattributedMiles.toFloat(), c.neutral, note = "${Formats.decimal(time.unattributedMiles, 1)} mi"),
        )
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)
    }
}

/** On-time: a gauge over deliveries that carried a captured deadline + the average finish margin. */
@Composable
private fun OnTimeCard(time: TimeEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "ON TIME", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        val rate = time.onTimeRate
        if (rate == null) {
            EmptyRow("No deliveries carried a deadline in this period.")
            return@AppCard
        }

        AppGaugeRing(
            progress = rate.toFloat(),
            value = "${(rate * 100.0).roundToInt()}%",
            label = "on time",
            color = c.good,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${Formats.commaInt(time.onTimeDeliveries)} of ${Formats.commaInt(time.deliveriesWithDeadline)} " +
                (if (time.deliveriesWithDeadline == 1) "delivery" else "deliveries") + " with a deadline",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )

        // Average finish margin (deadline − completedAt): positive ⇒ typically early.
        time.avgDeadlineMarginMillis?.let { margin ->
            Spacer(Modifier.height(4.dp))
            val early = margin >= 0.0
            val magnitude = kotlin.math.abs(margin).roundToLong()
            Text(
                text = if (early) "typically ${formatDuration(magnitude)} early"
                else "typically ${formatDuration(magnitude)} late",
                style = MaterialTheme.typography.bodySmall,
                color = if (early) c.good else c.bad,
            )
        }
    }
}

/** Mileage & tax: the period's session odometer miles + the estimated IRS standard-mileage deduction. */
@Composable
private fun MileageTaxCard(time: TimeEconomics, period: AnalyticsPeriod) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = "MILEAGE & TAX", style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (time.miles <= 0.0) {
            EmptyRow("No miles measured in this period yet.")
            return@AppCard
        }

        // Current year from the device clock at render (a review surface — no ticker needed).
        val labels = MileageTaxModel.from(time.miles, LocalDate.now().year, period)

        Text(text = "${Formats.decimal(time.miles, 1)} mi", style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text(text = labels.deductionLine, style = MaterialTheme.typography.bodyMedium, color = c.text)
        labels.disclaimer?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
        labels.spansYearsNote?.let {
            Spacer(Modifier.height(4.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = c.text3)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "standard-mileage method — not the app's operating-cost model; confirm with a tax preparer.",
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/**
 * Pure copy logic for the MILEAGE & TAX card (#689) — Compose-free so it's unit-testable in
 * isolation from rendering (the [WaterfallModel] precedent). The card covers a selected *period*,
 * which is single-year by construction for Today/Week/Month; only Lifetime can span years.
 *
 * Locked policy: label the deduction with the **current** year's rate via the [IrsMileage] lookup
 * (never a literal year/rate). When the current year has no published IRS rate yet
 * (`!IrsMileage.isKnown`), append an explicit disclaimer — the deduction still computes off the
 * latest known rate ([IrsMileage.deduction]'s fallback) but the substitution is stated. For
 * Lifetime, add a note that the period may span tax years and point at the CSV export (which owns
 * the per-year precision) — cheap honesty over re-costing every row here.
 */
object MileageTaxModel {

    data class Labels(
        /** "$X.XX est. IRS <year> standard-mileage deduction ($0.725/mi)". */
        val deductionLine: String,
        /** Non-null only when the current year's rate isn't published — the honest-fallback note. */
        val disclaimer: String?,
        /** Non-null only for Lifetime, which can straddle a year boundary. */
        val spansYearsNote: String?,
    )

    fun from(miles: Double, currentYear: Int, period: AnalyticsPeriod): Labels {
        val rate = IrsMileage.rateFor(currentYear) ?: IrsMileage.latestKnown().second
        val deductionLine = "${Formats.money(IrsMileage.deduction(miles, currentYear))} " +
            "est. IRS $currentYear standard-mileage deduction (${Formats.money3(rate)}/mi)"
        val disclaimer = if (IrsMileage.isKnown(currentYear)) {
            null
        } else {
            "$currentYear rate not yet published — estimated at the ${IrsMileage.latestKnown().first} rate"
        }
        val spansYearsNote = if (period == AnalyticsPeriod.LIFETIME) {
            "spans tax years — see the CSV export for per-year figures"
        } else {
            null
        }
        return Labels(deductionLine, disclaimer, spansYearsNote)
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = AppTheme.colors.text3,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
