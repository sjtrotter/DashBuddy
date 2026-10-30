package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.feature.bubble.notification.rateParts
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun OfferGauge(model: GaugeModel) {
    val c = AppTheme.colors
    val ink = when (model.tone) { GaugeTone.GOOD -> c.good; GaugeTone.WARN -> c.warn; GaugeTone.NEUTRAL -> c.neutral }
    val unit = when (model.kind) {
        GaugeKind.HOUR -> stringResource(R.string.hud_unit_min)
        GaugeKind.MILE -> stringResource(R.string.hud_offer_unit_mi)
        GaugeKind.ITEM -> pluralStringResource(R.plurals.hud_offer_items, model.denominatorText.toIntOrNull() ?: 0)
    }
    val rate = model.rateParts()
    val state = model.aboveTarget?.let { stringResource(if (it) R.string.hud_offer_cd_above else R.string.hud_offer_cd_below) }
    val targetNote = if (model.kind == GaugeKind.ITEM && model.tickAt != null)
        stringResource(R.string.hud_offer_cd_item_target) else null
    Row(Modifier.fillMaxWidth().testTag("offer.gauge.${model.kind}").semantics {
        state?.let { stateDescription = it }
        targetNote?.let { contentDescription = it }
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(76.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                Text(model.denominatorText, style = AppTheme.num.lgNum, color = c.text)
                Text(unit, color = c.text3, fontSize = 12.sp)
            }
            model.deltaText?.let { Text(it, style = AppTheme.num.smNum, color = c.stOffer) }
        }
        Canvas(Modifier.weight(1f).height(20.dp)) {
            // Keep hollow markers and ticks within the bar's measured bounds.
            val inset = 7.dp.toPx()
            val span = (size.width - inset * 2).coerceAtLeast(0f)
            val y = size.height / 2
            val height = 10.dp.toPx()
            val radius = CornerRadius(5.dp.toPx())
            drawRoundRect(c.surface3, Offset(inset, y - height / 2), Size(span, height), radius)
            if (model.fill > 0) drawRoundRect(ink, Offset(inset, y - height / 2), Size(span * model.fill, height), radius)
            model.tickAt?.let { drawLine(c.text, Offset(inset + span * it, y - 9.dp.toPx()),
                Offset(inset + span * it, y + 9.dp.toPx()), 2.dp.toPx()) }
            model.ghostAt?.let {
                val center = Offset(inset + span * it, y)
                drawCircle(c.surface, 7.dp.toPx(), center)
                drawCircle(c.neutral, 6.dp.toPx(), center, style = Stroke(2.dp.toPx()))
            }
        }
        Row(Modifier.width(84.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.Bottom) {
            Text(rate.value, style = AppTheme.num.lgNum, color = ink)
            Text(rate.unit, color = c.text3, fontSize = 11.sp)
        }
    }
}
