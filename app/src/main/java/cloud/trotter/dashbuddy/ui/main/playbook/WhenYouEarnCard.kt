package cloud.trotter.dashbuddy.ui.main.playbook

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.core.designsystem.component.AppCard
import cloud.trotter.dashbuddy.core.designsystem.component.AppChip
import cloud.trotter.dashbuddy.core.designsystem.component.AppHeatScale
import cloud.trotter.dashbuddy.core.designsystem.component.AppSegmented
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.SavedWeeklyPlan
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.hourOfDayLabel
import cloud.trotter.dashbuddy.ui.components.HeatmapGrid
import cloud.trotter.dashbuddy.ui.components.HeatmapHourAxis
import cloud.trotter.dashbuddy.ui.components.PatternsModel
import cloud.trotter.dashbuddy.ui.components.PatternsModel.HeatmapMode
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * **When you earn** (#1024 section C) — the lifetime hour×day heatmap with its Rate/Hours toggle, moved
 * here verbatim from the retired Patterns tab (#315 H5 / #979) and given one addition: the saved plan's
 * picked cells are **outlined**, so "where the plan came from" is visible on the same screen the plan is.
 *
 * The grid itself is the shared [HeatmapGrid] (#979/#981) — the same render the Weekly Plan screen
 * draws, which is precisely what makes the outline a proof rather than a decoration: the driver
 * recognises the picture, and two independently-written grids would eventually stop agreeing about a
 * colour, a mask or the day order (Principle 5).
 *
 * **Rate**: tinted by the driver's own realized net $/hr. A cell below the coverage floor renders as
 * *insufficient* (near-empty), visually distinct from a genuinely-zero cell (worked, earned ~nothing),
 * which gets a `bad` border. The ramp is scaled to the driver's own best hour — "your best/worst times",
 * never an absolute-dollar claim.
 *
 * **Hours**: tinted by coverage, scaled to the driver's own most-covered hour. There is no "worked, no
 * net" third state — coverage has no bad outcome — so a zero-coverage cell folds into the same "no data"
 * swatch ([PatternsModel.cellValue] does that on purpose, reusing [AppHeatScale] unchanged).
 *
 * The copy keeps its `patterns_tab_*` string ids: the words are unchanged and the id is the copy's
 * identity, so renaming would churn every translation for nothing.
 */
@Composable
fun WhenYouEarnCard(
    heatmap: EarningsHeatmap,
    plan: SavedWeeklyPlan?,
    modifier: Modifier = Modifier,
) {
    val c = AppTheme.colors
    var mode by rememberSaveable { mutableStateOf(HeatmapMode.RATE) }
    AppCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                if (mode == HeatmapMode.RATE) R.string.patterns_tab_heatmap_title else R.string.patterns_tab_heatmap_title_hours,
            ),
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

        HeatmapGrid(
            heatmap = heatmap,
            mode = mode,
            maxForMode = maxForMode,
            // No saved plan means no outline — never an invented one.
            outlined = plan?.let { saved -> { dayIndex, hour -> saved.covers(dayIndex, hour) } },
        )
        Spacer(Modifier.height(6.dp))
        HeatmapHourAxis()
        Spacer(Modifier.height(12.dp))
        if (mode == HeatmapMode.RATE) HeatmapLegend(maxRate) else HeatmapHoursLegend()

        // Best-hour callout is a Rate concept (the single most-earning cell) — a $/hr figure under a
        // coverage-tinted grid would read as mismatched, so it stays Rate-mode only.
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
            text = stringResource(
                if (mode == HeatmapMode.RATE) R.string.patterns_tab_heatmap_caption else R.string.patterns_tab_heatmap_caption_hours,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
        )
        // Only claim the outline exists when it does — otherwise the legend would describe a mark the
        // grid isn't drawing.
        if (plan != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.playbook_heatmap_plan_outline_caption),
                style = MaterialTheme.typography.bodySmall,
                color = c.text3,
            )
        }
    }
}

/**
 * Declares the Playbook's lifetime surfaces as lifetime **on purpose** (#979): these read the driver's
 * whole record and are not a view of any selected period. Stating it is what keeps "why didn't this
 * change?" from reading as a bug.
 */
@Composable
fun AllTimeBadge(modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AppChip(text = stringResource(R.string.patterns_tab_all_time_badge))
        Text(
            text = stringResource(R.string.patterns_tab_all_time_caption),
            style = MaterialTheme.typography.bodySmall,
            color = c.text3,
            modifier = Modifier.weight(1f),
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
 * coverage ramp — no covered-but-bad third state, since coverage has no bad outcome.
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
