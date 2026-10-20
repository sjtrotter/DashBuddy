package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
internal fun PayBar(model: PayBarModel) {
    val c = AppTheme.colors
    val profit = (model as? PayBarModel.Split)?.profitCents
    val loss = (model as? PayBarModel.Loss)?.lossCents
    val car = when (model) { is PayBarModel.Split -> model.carCents; is PayBarModel.Loss -> model.carCents; else -> null }
    val description = when (model) {
        is PayBarModel.Split -> stringResource(R.string.hud_offer_cd_bar, payBarMoney(model.profitCents), payBarMoney(model.carCents))
        is PayBarModel.Loss -> stringResource(R.string.hud_offer_cd_bar_loss, payBarMoney(model.lossCents), payBarMoney(model.carCents))
        PayBarModel.DistanceUnknown -> stringResource(R.string.hud_offer_distance_unknown)
        PayBarModel.Unscored -> ""
    }
    Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp)).testTag("offer.bar")
        .semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(c.surface3)
            when (model) {
                is PayBarModel.Split -> {
                    val (left, right) = PayBarLayout.split(size.width, 6.dp.toPx(), model.profitFraction)
                    drawRect(c.good.copy(alpha = 0.28f), size = Size(left, size.height))
                    drawRect(c.warn.copy(alpha = 0.28f), topLeft = Offset(left, 0f), size = Size(right, size.height))
                }
                is PayBarModel.Loss -> drawRect(c.bad.copy(alpha = 0.22f))
                else -> Unit
            }
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 5.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            if (profit != null || loss != null) {
                PayPill(payBarMoney(profit ?: loss!!), stringResource(if (loss != null) R.string.hud_offer_loss else R.string.hud_offer_profit),
                    if (loss != null) c.bad else c.good, true)
                car?.let { PayPill(payBarMoney(it), stringResource(R.string.hud_offer_car), c.warn, false) }
            } else if (model == PayBarModel.DistanceUnknown) {
                Text(stringResource(R.string.hud_offer_distance_unknown), color = c.neutral, fontSize = 14.sp)
            }
        }
    }
}

internal fun payBarMoney(cents: Long) = Formats.money(cents / 100.0)

@Composable
private fun PayPill(value: String, label: String, ink: Color, primary: Boolean) {
    Surface(shape = RoundedCornerShape(12.dp), color = AppTheme.colors.bg.copy(alpha = 0.72f),
        border = BorderStroke(1.5.dp, ink), modifier = Modifier.height(24.dp)) {
        Row(Modifier.padding(horizontal = 7.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = if (primary) AppTheme.num.lgNum else AppTheme.num.smNum.copy(
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = ink)
            Text(label, fontSize = 11.sp, color = ink)
        }
    }
}
