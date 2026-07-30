package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import kotlin.math.roundToInt

/** A single bar. [highlight] paints it in the brand accent (e.g. a "hot" day/zone). */
data class AppBar(val label: String, val value: Float, val highlight: Boolean = false)

/**
 * Simple labelled bar chart — earnings-by-period, etc.
 *
 * [onBarClick] makes the bars **selectable** (#973 / brief §4.2 — a tappable day bar whose detail the
 * caller renders beside the chart). It receives the bar's index; the caller owns the selection state
 * and passes it back as [selectedIndex], which draws the selection outline. Null (the default) leaves
 * the chart inert, exactly as before — the whole tap affordance, including its accessibility label
 * ([clickLabel]), is opt-in and caller-supplied, so this stays a data-in / lambdas-out component.
 */
@Composable
fun AppBarChart(
    bars: List<AppBar>,
    modifier: Modifier = Modifier,
    height: Dp = 96.dp,
    onBarClick: ((Int) -> Unit)? = null,
    selectedIndex: Int? = null,
    clickLabel: String? = null,
) {
    val c = AppTheme.colors
    val max = bars.maxOfOrNull { it.value }?.takeIf { it > 0f } ?: 1f
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { index, b ->
                // The whole column is the tap target (a 4dp-wide bar is not), so a thin bar on a big
                // day stays reachable.
                val cell = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .let { base ->
                        if (onBarClick == null) {
                            base
                        } else {
                            base.clickable(onClickLabel = clickLabel, role = Role.Button) { onBarClick(index) }
                        }
                    }
                Box(cell, contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight((b.value / max).coerceIn(0.02f, 1f))
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(if (b.highlight) c.accent else c.surface3)
                            // Selection is an OUTLINE, never a recolour: the accent fill already means
                            // "best bar", so tinting a selected bar would overload one signal with two
                            // meanings.
                            .then(
                                if (index == selectedIndex) {
                                    Modifier.border(2.dp, c.lineStrong, MaterialTheme.shapes.extraSmall)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            bars.forEach { b ->
                Text(
                    b.label,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.text3,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Compact trend line — the recap hero's per-day sparkline (#970).
 *
 * Deliberately axis-less and label-less: a sparkline states *shape*, and the real number always sits
 * beside it (never inferred from the line). Values may be negative (a day whose costs beat its pay),
 * so the baseline is `min(0, min(values))` and the line is drawn against the full min→max span — a
 * losing day visibly dips instead of clamping flat. A single point, an all-equal series, or an empty
 * list draws a flat mid-height line rather than dividing by a zero span.
 */
@Composable
fun AppSparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.accent,
    height: Dp = 34.dp,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier.height(height).fillMaxWidth()) {
        if (values.isEmpty()) return@Canvas
        val low = minOf(0.0, values.min())
        val high = maxOf(values.max(), low)
        val span = (high - low).takeIf { it > 0.0 }
        val stroke = strokeWidth.toPx()
        val usableHeight = (size.height - stroke).coerceAtLeast(1f)
        fun yFor(value: Double): Float {
            val fraction = span?.let { ((value - low) / it).toFloat() } ?: 0.5f
            return (stroke / 2f) + usableHeight * (1f - fraction.coerceIn(0f, 1f))
        }
        if (values.size == 1) {
            val y = yFor(values.first())
            drawLine(color, Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
            return@Canvas
        }
        val step = size.width / (values.size - 1)
        var previous = Offset(0f, yFor(values.first()))
        for (index in 1 until values.size) {
            val next = Offset(step * index, yFor(values[index]))
            drawLine(color, previous, next, stroke, StrokeCap.Round)
            previous = next
        }
    }
}

/** Circular progress gauge with a centred value + caption — ratings, on-time scorecard. */
@Composable
fun AppGaugeRing(
    progress: Float,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    color: Color = AppTheme.colors.accent,
    diameter: Dp = 88.dp,
) {
    val c = AppTheme.colors
    val track = c.surface3
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2f, stroke / 2f)
                drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(
                    color, -90f, 360f * progress.coerceIn(0f, 1f), false,
                    topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            Text(value, style = AppTheme.num.lgNum, color = c.text)
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.text2)
    }
}

/** A proportion in a stacked bar / legend. */
data class AppSegment(val label: String, val value: Float, val color: Color, val note: String? = null)

/** Stacked proportion bar — offer funnel, time split, deadhead ratio. */
@Composable
fun AppStackBar(segments: List<AppSegment>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val total = segments.sumOf { it.value.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    Row(modifier.clip(CircleShape).fillMaxWidth().height(height)) {
        segments.forEach { s ->
            Box(Modifier.weight((s.value / total).coerceAtLeast(0.0001f)).fillMaxHeight().background(s.color))
        }
    }
}

/** Legend for a [AppStackBar]. Shows each key + its `note` (or computed percent). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppLegend(segments: List<AppSegment>, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val total = segments.sumOf { it.value.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        segments.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(9.dp).clip(MaterialTheme.shapes.extraSmall).background(s.color))
                Text(s.label, style = MaterialTheme.typography.bodySmall, color = c.text2)
                Text(
                    s.note ?: "${(s.value / total * 100f).roundToInt()}%",
                    style = AppTheme.num.smNum,
                    color = c.text,
                )
            }
        }
    }
}

@Preview
@Composable
private fun AppChartsPreview() = DashBuddyTheme {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppBarChart(
                listOf(
                    AppBar("M", 96f), AppBar("T", 110f), AppBar("W", 88f),
                    AppBar("T", 132f, highlight = true), AppBar("F", 158f, highlight = true),
                ),
            )
            AppGaugeRing(progress = 0.96f, value = "96%", label = "Before deadline", color = AppTheme.colors.good)
            val segs = listOf(
                AppSegment("Accepted", 54f, AppTheme.colors.good),
                AppSegment("Declined", 32f, AppTheme.colors.bad),
                AppSegment("Timed out", 5f, AppTheme.colors.neutral),
            )
            AppStackBar(segs, height = 14.dp)
            AppLegend(segs)
        }
    }
}
