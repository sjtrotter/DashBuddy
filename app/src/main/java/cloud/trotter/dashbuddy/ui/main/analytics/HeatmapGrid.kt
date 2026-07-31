package cloud.trotter.dashbuddy.ui.main.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.component.AppHeatScale
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.format.hourOfDayLabel
import cloud.trotter.dashbuddy.ui.main.analytics.PatternsModel.HeatmapMode
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Local day order: Monday-first, matching the app's Monday-anchored week (#655 / PeriodBounds). */
internal val DAY_ROWS = listOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY,
)

/** Width of the day-label gutter, shared by the grid and its axis so the ticks line up. */
private val DAY_LABEL_WIDTH = 30.dp

/**
 * The 7 day-rows × 24 hour-columns heat grid — extracted from `PatternsTab` (#979) so the Weekly Plan
 * screen (#981, brief §8 item 4: "the lifetime heatmap with the picked cells outlined") renders the
 * SAME grid the Patterns tab does rather than a lookalike.
 *
 * That reuse is the point: the plan claims to be derived from the driver's own record, and the way it
 * proves that is by showing the *same picture* the Patterns tab shows, with the chosen cells ringed.
 * Two independently-written grids would eventually disagree about a colour, a mask or a day order, and
 * the proof would quietly stop being one (Principle 5).
 *
 * [outlined] rings a cell — the Weekly Plan's picked hours. `null` (the default) draws no outline, which
 * is exactly the Patterns tab's behaviour, so extracting it changed nothing there.
 */
@Composable
internal fun HeatmapGrid(
    heatmap: EarningsHeatmap,
    mode: HeatmapMode,
    maxForMode: Double,
    modifier: Modifier = Modifier,
    outlined: ((dayIndex: Int, hour: Int) -> Boolean)? = null,
) {
    val c = AppTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        DAY_ROWS.forEach { day ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text3,
                    modifier = Modifier.width(DAY_LABEL_WIDTH),
                )
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (hour in 0 until EarningsHeatmap.HOURS) {
                        val dayIndex = day.value - 1
                        val cell = heatmap.cell(dayIndex, hour)
                        val value = PatternsModel.cellValue(cell, mode)
                        // "Worked, no net" is a Rate-only third state — Hours has no bad outcome to
                        // border, only more/less coverage.
                        val cellRate = cell.dollarsPerHour
                        val zeroRate = mode == HeatmapMode.RATE && cellRate != null && cellRate <= 0.0
                        val isPicked = outlined?.invoke(dayIndex, hour) == true
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppHeatScale.cellColor(value, maxForMode, c))
                                .then(
                                    when {
                                        // A picked cell wins the border: on this screen it is the
                                        // information the grid exists to carry.
                                        isPicked -> Modifier.border(1.dp, c.accent, RoundedCornerShape(2.dp))
                                        zeroRate -> Modifier.border(1.dp, c.bad, RoundedCornerShape(2.dp))
                                        else -> Modifier
                                    },
                                ),
                        )
                    }
                }
            }
        }
    }
}

/** Four evenly-spaced clock ticks under the grid (offset past the day-label gutter). */
@Composable
internal fun HeatmapHourAxis() {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(DAY_LABEL_WIDTH))
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            // labelSmall, no raw fontSize override — matches the sibling AppBarChart axis-label treatment.
            listOf(0, 6, 12, 18).forEach { h ->
                Text(text = hourOfDayLabel(h), style = MaterialTheme.typography.labelSmall, color = c.text3)
            }
            Text(text = hourOfDayLabel(0), style = MaterialTheme.typography.labelSmall, color = c.text3)
        }
    }
}
