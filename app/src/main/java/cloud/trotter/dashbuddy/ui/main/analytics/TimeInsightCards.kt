package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.components.DisclosureRow
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppLegend
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegment
import cloud.trotter.dashbuddy.core.designsystem.component.AppStackBar
import cloud.trotter.dashbuddy.core.designsystem.component.AppStatTile
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.GapStats
import cloud.trotter.dashbuddy.domain.analytics.HourComposition
import cloud.trotter.dashbuddy.domain.analytics.NetPerHourPair
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration

/**
 * The Time tab's #983 additions (brief §6 + §7.8) — the last stage of the #969 redesign, kept in their
 * own file so `TimeTab.kt` stays the small host it was (Principle 3).
 *
 * Three cards, all pure data-in renderers over `:domain` models (Principle 1 — UDF), all MEASURED:
 *
 * 1. [NetPerHourPairCard] — the `while working` / `whole shift` pair that
 *    `docs/design/running-hourly-rate.md` §2a defines and the app has never shown.
 * 2. [TypicalOnlineHourCard] — where an online hour goes, with the waiting leak stated in dollars.
 * 3. [GapsBetweenJobsCard] — the §7.8 gap distribution.
 *
 * **Honesty rules these cards implement (§9):** every derived figure states its population (how many
 * stops were timed, how many gaps were measured, how many drops produced none), the residual segment
 * is never labelled as pure driving, a long gap is counted but explicitly not judged as a break, and a
 * figure with no measured denominator renders [EMPTY_VALUE] with a sentence saying so rather than a
 * `$0.00` that would read as a measurement. Aggregate-only — durations, counts, dollars; no merchant
 * or customer text (Principle 6). Every number routes through the [Formats] / [formatDuration] SSOT.
 *
 * Not a ticking surface: a review window's rates are fixed values, so no `rememberNow()` (same
 * reasoning as the rest of the hub).
 */
@Composable
fun NetPerHourPairCard(rates: NetPerHourPair, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var measurementExpanded by remember { mutableStateOf(false) }
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.time_tab_rate_pair_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        if (rates.onlineMillis <= 0L) {
            EmptyRow(stringResource(R.string.time_tab_rate_none))
            return@AppCard
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = stringResource(R.string.time_tab_rate_while_working),
                value = rates.whileWorking?.let { "${Formats.money(it)}/hr" } ?: EMPTY_VALUE,
                sub = rates.workingMillis.takeIf { it > 0L }?.let { formatDuration(it) },
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.time_tab_rate_whole_shift),
                value = rates.wholeShift?.let { "${Formats.money(it)}/hr" } ?: EMPTY_VALUE,
                sub = formatDuration(rates.onlineMillis),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))

        if (rates.workingTimeUnmeasured) {
            Spacer(Modifier.height(4.dp))
            Caption(stringResource(R.string.time_tab_rate_working_unmeasured))
        }

        // COLLAPSE: the while-working/whole-shift distinction + the gap-denominator note move behind
        // one DisclosureRow ("How these are measured") instead of stacking as four separate captions.
        // F1 also drops the redundant frozen-net note here — the recap hero states it for this window.
        Spacer(Modifier.height(4.dp))
        DisclosureRow(
            text = stringResource(R.string.time_tab_rate_measurement_detail),
            expanded = measurementExpanded,
            onToggle = { measurementExpanded = !measurementExpanded },
        )
        if (measurementExpanded) {
            Spacer(Modifier.height(4.dp))
            Caption(stringResource(R.string.time_tab_rate_distinction))
            Spacer(Modifier.height(4.dp))
            Caption(
                if (rates.gapsSubtracted > 0) {
                    stringResource(
                        R.string.time_tab_rate_gaps_note_format,
                        Formats.commaInt(rates.gapsSubtracted),
                        pluralGap(rates.gapsSubtracted),
                    )
                } else {
                    stringResource(R.string.time_tab_rate_no_gaps_note)
                },
            )
        }
    }
}

/**
 * "Your typical online hour" (brief §6) — a 3-segment bar over the window's online time, scaled to one
 * hour, plus the waiting leak in dollars.
 *
 * The segment labels follow [HourComposition]'s derivation, not the brief's shorthand: the brief says
 * `driving / at store / waiting`, but the measured door dwell belongs with the store dwell (so the
 * segment is "at stops") and the third segment is a residual that also holds unattributed time (so it
 * is "driving & other", with a caption saying what else is in it). The alternative — labelling a
 * residual "driving" — would be the one thing this surface must not do.
 */
@Composable
fun TypicalOnlineHourCard(composition: HourComposition, rates: NetPerHourPair, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    var derivationExpanded by remember { mutableStateOf(false) }
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.time_tab_typical_hour_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        if (!composition.hasData) {
            EmptyRow(stringResource(R.string.time_tab_typical_hour_none))
            return@AppCard
        }

        // Values are the shares scaled to one hour, so the bar IS the sentence "in a typical hour…".
        val segments = listOf(
            AppSegment(
                label = stringResource(R.string.time_tab_segment_driving),
                value = composition.restMillisPerHour.toFloat(),
                color = c.neutral,
                note = formatDuration(composition.restMillisPerHour),
            ),
            AppSegment(
                label = stringResource(R.string.time_tab_segment_at_stops),
                value = composition.atStopsMillisPerHour.toFloat(),
                color = c.good,
                note = formatDuration(composition.atStopsMillisPerHour),
            ),
            AppSegment(
                label = stringResource(R.string.time_tab_segment_waiting),
                value = composition.waitingMillisPerHour.toFloat(),
                color = c.warn,
                note = formatDuration(composition.waitingMillisPerHour),
            ),
        )
        AppStackBar(segments, height = 14.dp)
        Spacer(Modifier.height(10.dp))
        AppLegend(segments)
        Spacer(Modifier.height(12.dp))

        // The leak, in dollars, priced at the while-working rate and scoped to this window (§9).
        val leak = rates.valueOf(composition.waitingMillis)
        Text(
            text = if (leak == null) {
                stringResource(R.string.time_tab_typical_hour_leak_unpriced)
            } else {
                stringResource(
                    R.string.time_tab_typical_hour_leak_format,
                    Formats.money(leak),
                    formatDuration(composition.waitingMillis),
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = c.text,
        )
        Spacer(Modifier.height(8.dp))

        // COLLAPSE: the 279-char derivation moves behind a tap; the short line + the "Driving &
        // other" segment label keep the residual-is-not-pure-driving honesty visible by default.
        DisclosureRow(
            text = stringResource(R.string.time_tab_typical_hour_rest_short),
            expanded = derivationExpanded,
            onToggle = { derivationExpanded = !derivationExpanded },
        )
        if (derivationExpanded) {
            Spacer(Modifier.height(4.dp))
            Caption(stringResource(R.string.time_tab_typical_hour_derivation))
        }
        Spacer(Modifier.height(4.dp))
        Caption(stopCoverageText(composition))
    }
}

/** The §7.8 gap distribution — median / p90 / longest, with the population and the long-gap caveat. */
@Composable
fun GapsBetweenJobsCard(gaps: GapStats, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.time_tab_gaps_title),
            style = MaterialTheme.typography.labelMedium,
            color = c.text3,
        )
        Spacer(Modifier.height(10.dp))

        if (!gaps.hasGaps) {
            EmptyRow(stringResource(R.string.time_tab_gaps_none))
            return@AppCard
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStatTile(
                label = stringResource(R.string.time_tab_gaps_median_label),
                value = gaps.medianMillis?.let { formatDuration(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.time_tab_gaps_p90_label),
                value = gaps.p90Millis?.let { formatDuration(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
            AppStatTile(
                label = stringResource(R.string.time_tab_gaps_longest_label),
                value = gaps.longestMillis?.let { formatDuration(it) } ?: EMPTY_VALUE,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        // CONDENSE (merge): the standalone "what a gap measures" definition folds into this one
        // coverage line rather than sitting above it as a second caption (one caveat per card).
        Caption(
            stringResource(
                R.string.time_tab_gaps_coverage_format,
                Formats.commaInt(gaps.count),
                pluralGap(gaps.count),
                Formats.commaInt(gaps.completionsConsidered),
            ),
        )
        if (gaps.longGapCount > 0) {
            Spacer(Modifier.height(4.dp))
            Caption(
                stringResource(
                    R.string.time_tab_gaps_long_format,
                    Formats.commaInt(gaps.longGapCount),
                    pluralGap(gaps.longGapCount),
                ),
            )
        }
    }
}

/**
 * The at-stops coverage sentence — three states, because "we timed everything", "we timed most of it"
 * and "we timed none of it" are three genuinely different claims and only the last one makes the
 * segment meaningless (§9).
 */
@Composable
private fun stopCoverageText(composition: HourComposition): String = when {
    composition.stops == 0 -> stringResource(R.string.time_tab_typical_hour_no_stops)
    composition.noStopsTimed -> stringResource(R.string.time_tab_typical_hour_no_stops_timed)
    composition.allStopsTimed -> stringResource(
        R.string.time_tab_typical_hour_all_timed_format,
        Formats.commaInt(composition.stops),
    )

    else -> stringResource(
        R.string.time_tab_typical_hour_coverage_format,
        Formats.commaInt(composition.stopsTimed),
        Formats.commaInt(composition.stops),
    )
}

@Composable
private fun pluralGap(count: Int): String =
    if (count == 1) stringResource(R.string.time_tab_gap_singular)
    else stringResource(R.string.time_tab_gap_plural)

/** The tab's small explanatory line — one style, so every caption on these cards reads the same. */
@Composable
private fun Caption(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.text3)
}
