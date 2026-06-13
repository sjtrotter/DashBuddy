package cloud.trotter.dashbuddy.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.DashBuddyTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import kotlin.math.roundToInt

/** A single bar. [highlight] paints it in the brand accent (e.g. a "hot" day/zone). */
data class AppBar(val label: String, val value: Float, val highlight: Boolean = false)

/** Simple labelled bar chart — earnings-by-period, etc. */
@Composable
fun AppBarChart(bars: List<AppBar>, modifier: Modifier = Modifier, height: Dp = 96.dp) {
    val c = AppTheme.colors
    val max = bars.maxOfOrNull { it.value }?.takeIf { it > 0f } ?: 1f
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().height(height),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEach { b ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.7f)
                            .fillMaxHeight((b.value / max).coerceIn(0.02f, 1f))
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(if (b.highlight) c.accent else c.surface3),
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
