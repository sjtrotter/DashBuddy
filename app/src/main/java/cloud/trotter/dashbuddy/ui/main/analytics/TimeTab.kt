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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppGaugeRing
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.GapStats
import cloud.trotter.dashbuddy.domain.analytics.HourComposition
import cloud.trotter.dashbuddy.domain.analytics.NetPerHourPair
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.export.IrsMileage
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToLong


/**
 * Time tab (#315 H4, extended by #983): the measured time / mileage review for the selected period,
 * top→bottom — the online-time split (on-delivery vs unattributed), the #983 net/hr pair, "your
 * typical online hour", the §7.8 gap distribution, the deadhead ratio, the on-time gauge, and the
 * mileage-&-tax card. Pure data in ([TimeEconomics] / [GapStats] and the two models composed from
 * them), no side effects (Principle 1 — UDF).
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
    gaps: GapStats,
    hourComposition: HourComposition,
    netPerHour: NetPerHourPair,
    window: AnalyticsWindow,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TimeSplitCard(time)
        // #983 (brief §6 + §7.8) — the running-hourly-rate doc's pair, where the hour goes, and the
        // gaps that both of them are measured against. Rendered by `TimeInsightCards.kt`.
        NetPerHourPairCard(netPerHour)
        TypicalOnlineHourCard(hourComposition, netPerHour)
        GapsBetweenJobsCard(gaps)
        DeadheadCard(time)
        OnTimeCard(time)
        MileageTaxCard(time, window)
    }
}

/** Online-time split: hero online duration + an on-delivery / unattributed stack bar + dash tiles. */
@Composable
private fun TimeSplitCard(time: TimeEconomics) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.time_tab_time_split_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (time.onlineMillis == 0L) {
            EmptyRow(stringResource(R.string.time_tab_no_sessions_yet))
            return@AppCard
        }

        Text(text = formatDuration(time.onlineMillis), style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(
                R.string.time_tab_online_across_format,
                Formats.commaInt(time.sessions),
                if (time.sessions == 1) stringResource(R.string.time_tab_session_singular)
                else stringResource(R.string.time_tab_session_plural),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(14.dp))

        val onDeliveryLabel = stringResource(R.string.time_tab_segment_on_delivery)
        val onDeliveryMillis = time.deliveryMillis ?: 0L
        val segments = listOf(
            AppSegment(onDeliveryLabel, onDeliveryMillis.toFloat(), c.good, note = formatDuration(onDeliveryMillis)),
            AppSegment(stringResource(R.string.time_tab_segment_unattributed), time.unattributedMillis.toFloat(), c.neutral, note = formatDuration(time.unattributedMillis)),
        )
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = stringResource(R.string.time_tab_sessions_stat_label),
                value = Formats.commaInt(time.sessions),
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.time_tab_avg_session_stat_label),
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
        Text(text = stringResource(R.string.time_tab_deadhead_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (time.miles <= 0.0) {
            EmptyRow(stringResource(R.string.time_tab_no_miles_measured_yet))
            return@AppCard
        }

        val deadheadPct = Formats.percent(time.unattributedMiles / time.miles)
        Text(text = deadheadPct, style = AppTheme.num.heroNum, color = c.text)
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.time_tab_deadhead_caption),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        Spacer(Modifier.height(14.dp))

        // Attributed portion = total − unattributed (min of delivery-delta and total), so the bar
        // sums to the period's odometer miles cleanly.
        val onDeliveryMiles = time.miles - time.unattributedMiles
        val segments = listOf(
            AppSegment(stringResource(R.string.time_tab_segment_on_delivery), onDeliveryMiles.toFloat(), c.good, note = "${Formats.decimal(onDeliveryMiles, 1)} mi"),
            AppSegment(stringResource(R.string.time_tab_segment_deadhead), time.unattributedMiles.toFloat(), c.neutral, note = "${Formats.decimal(time.unattributedMiles, 1)} mi"),
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
        Text(text = stringResource(R.string.time_tab_on_time_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        val rate = time.onTimeRate
        if (rate == null) {
            EmptyRow(stringResource(R.string.time_tab_no_deadline_deliveries_yet))
            return@AppCard
        }

        AppGaugeRing(
            progress = rate.toFloat(),
            value = Formats.percent(rate),
            label = stringResource(R.string.time_tab_gauge_on_time_label),
            color = c.good,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                R.string.time_tab_deadline_caption_format,
                Formats.commaInt(time.onTimeDeliveries),
                Formats.commaInt(time.deliveriesWithDeadline),
                if (time.deliveriesWithDeadline == 1) stringResource(R.string.time_tab_delivery_singular)
                else stringResource(R.string.time_tab_delivery_plural),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )

        // Average finish margin (deadline − completedAt): positive ⇒ typically early.
        time.avgDeadlineMarginMillis?.let { margin ->
            Spacer(Modifier.height(4.dp))
            val early = margin >= 0.0
            val magnitude = kotlin.math.abs(margin).roundToLong()
            Text(
                text = if (early) stringResource(R.string.time_tab_margin_early_format, formatDuration(magnitude))
                else stringResource(R.string.time_tab_margin_late_format, formatDuration(magnitude)),
                style = MaterialTheme.typography.bodySmall,
                color = if (early) c.good else c.bad,
            )
        }
    }
}

/** Mileage & tax: the period's session odometer miles + the estimated IRS standard-mileage deduction. */
@Composable
private fun MileageTaxCard(time: TimeEconomics, window: AnalyticsWindow) {
    val c = AppTheme.colors
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.time_tab_mileage_tax_title), style = MaterialTheme.typography.labelMedium, color = c.text3)
        Spacer(Modifier.height(10.dp))
        if (time.miles <= 0.0) {
            EmptyRow(stringResource(R.string.time_tab_no_miles_measured_yet))
            return@AppCard
        }

        // Device clock at render (a review surface — no ticker needed).
        val labels = MileageTaxModel.from(
            miles = time.miles,
            nowMillis = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
            window = window,
        )

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
            text = stringResource(R.string.time_tab_mileage_tax_disclosure),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
    }
}

/**
 * Pure copy logic for the MILEAGE & TAX card (#689) — Compose-free so it's unit-testable in
 * isolation from rendering (the [MoneyWentModel] precedent). A single day or a calendar month is
 * single-year by construction, but the Monday-anchored week straddles Jan 1 whenever New Year's Day
 * falls Tue–Sun, a driver-drawn custom range can straddle anything, and Lifetime can span any
 * boundary — so the spans-years check derives from the selected [AnalyticsWindow]'s own endpoints
 * (the window SSOT), never from a granularity guess.
 *
 * **Rate year (#970):** the deduction is labelled with the year the *window* falls in, not the
 * device's current year. Before the pager existed every window was the current one, so reading the
 * clock was the same answer; once a driver can page back to last December, quoting this year's rate
 * over last year's miles would be a wrong number with a confident label. An unbounded (Lifetime) or
 * year-straddling window falls back to the current year AND carries the spans-years note, which
 * points at the CSV export (which owns the per-year precision) — cheap honesty over re-costing every
 * row here.
 *
 * Locked policy: the rate itself always comes from the [IrsMileage] lookup (never a literal
 * year/rate); the fallback policy and disclaimer copy are [IrsMileage.effectiveRate] /
 * [IrsMileage.fallbackNote] (one owner — the CSV renders the identical note).
 */
object MileageTaxModel {

    data class Labels(
        /** "$X.XX est. IRS <year> standard-mileage deduction ($0.725/mi)". */
        val deductionLine: String,
        /** Non-null only when the labelled year has no rate in the table — [IrsMileage.fallbackNote]. */
        val disclaimer: String?,
        /** Non-null when the window spans (Lifetime: may span) a tax-year boundary. */
        val spansYearsNote: String?,
    )

    fun from(miles: Double, nowMillis: Long, zone: ZoneId, window: AnalyticsWindow): Labels {
        val currentYear = Instant.ofEpochMilli(nowMillis).atZone(zone).year
        val startYear = window.startDate?.year
        val endYear = window.endDateInclusive?.year
        val spansYears = startYear == null || endYear == null || startYear != endYear
        // A window wholly inside one year is labelled with THAT year's rate; anything ambiguous falls
        // back to the current year and says so.
        val year = if (spansYears) currentYear else startYear
        val deductionLine = "${Formats.money(IrsMileage.deduction(miles, year))} " +
            "est. IRS $year standard-mileage deduction (${Formats.money3(IrsMileage.effectiveRate(year))}/mi)"
        val spansYearsNote = when {
            window.isLifetime -> "may span tax years — see the CSV export for per-year figures"
            spansYears -> "spans tax years — see the CSV export for per-year figures"
            else -> null
        }
        return Labels(deductionLine, IrsMileage.fallbackNote(year), spansYearsNote)
    }
}

