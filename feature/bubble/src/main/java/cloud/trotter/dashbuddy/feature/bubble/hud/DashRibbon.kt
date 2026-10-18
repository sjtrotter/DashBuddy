package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun DashRibbon(model: RibbonModel) {
    val now by rememberNow()
    val time = rememberTimeFormatter()
    val start = time(model.startAt)
    val hm = HoursMinutes.of(now - model.startAt)
    val duration = "${hm.hours} ${stringResource(R.string.hud_unit_hr)} ${hm.minutes} ${stringResource(R.string.hud_unit_min)}"
    val description = stringResource(R.string.hud_ribbon_cd, start, duration)
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().height(42.dp).testTag("hud.ribbon")
        .clearAndSetSemantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val geometry = RibbonLayout.layout(model, now, size.width, 6.dp.toPx(), 1.5.dp.toPx(), 8.5.dp.toPx())
            fun color(kind: RibbonKind) = when (kind) {
                RibbonKind.WAIT -> c.good
                RibbonKind.PICKUP -> c.stPickup
                RibbonKind.DROPOFF -> c.stDropoff
            }
            val y = 16.dp.toPx()
            geometry.rects.forEach { rect ->
                val ink = color(rect.kind).let { if (rect.kind == RibbonKind.WAIT && !rect.live) it.copy(alpha = 0.3f) else it }
                drawRect(ink, Offset(rect.left, y - 4.dp.toPx()), Size(rect.right - rect.left, 8.dp.toPx()))
            }
            geometry.marks.forEach { mark ->
                val point = Offset(mark.x, 4.5.dp.toPx())
                if (mark.accepted) drawCircle(c.stOffer, 3.5.dp.toPx(), point)
                else {
                    drawCircle(c.bg, 2.9.dp.toPx(), point)
                    drawCircle(c.stOffer, 2.9.dp.toPx(), point, style = Stroke(1.4.dp.toPx()))
                }
            }
            geometry.knobX?.let { x ->
                drawCircle(color(model.tail!!), 6.dp.toPx(), Offset(x, y))
                drawCircle(c.bg, 6.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
            }
        }
        Text(start, style = MaterialTheme.typography.labelSmall, color = c.text3)
    }
}
