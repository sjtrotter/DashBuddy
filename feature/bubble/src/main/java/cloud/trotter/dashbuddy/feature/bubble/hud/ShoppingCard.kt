package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
internal fun ShoppingCard(model: StateCardModel.Shopping) {
    val now by rememberNow()
    StateLine(model.facts.store, R.string.hud_state_due, model.facts.deadlineAt)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom) {
        Row(Modifier.testTag("state.figure"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.shopped.toString(), style = AppTheme.num.stateNum, color = AppTheme.colors.text,
                modifier = Modifier.alignByBaseline())
            Text(stringResource(R.string.hud_state_of_total, model.total), color = AppTheme.colors.text2,
                style = AppTheme.num.cardNum.copy(fontSize = AppTheme.num.cardNum.fontSize * (26f / 15f)), modifier = Modifier.alignByBaseline())
            Text(stringResource(R.string.hud_state_shopped), color = AppTheme.colors.text2,
                style = AppTheme.num.stateUnit, modifier = Modifier.alignByBaseline())
        }
        if (LocalHudTier.current < FontScaleTier.NO_JOB_COLUMN) JobColumn(model.facts)
    }
    ItemsTrack(model.shopped, model.total)
    val late = StateHeadline.lateMinutes(model.facts.deadlineAt, now)
    val dwell = StateHeadline.parked(model.arrivedAt, model.facts.deadlineAt, now)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StateNumber(headlineText(dwell))
            StateLabel(stringResource(if (late == null) R.string.hud_state_at_store else R.string.hud_state_min_at_store))
        }
        if (late != null) LateFoot(late)
        else ShopPace.itemsPerMinute(model.shopped, model.arrivedAt, now)?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StateNumber(Formats.decimal(it))
                StateLabel(stringResource(R.string.hud_state_items_min))
            }
        }
    }
}

@Composable
private fun ItemsTrack(shopped: Int, total: Int) {
    val c = AppTheme.colors
    val fill = (shopped.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().padding(top = 16.dp).height(10.dp).testTag("state.items")
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fill, 0f..1f) }) {
        val segments = ShopPace.segments(total)
        val corner = CornerRadius(2.dp.toPx())
        if (segments == null) {
            drawRoundRect(c.surface3, cornerRadius = corner)
            if (fill > 0) drawRoundRect(c.stPickup, size = Size(size.width * fill, size.height), cornerRadius = corner)
        } else {
            val gap = 3.dp.toPx()
            val width = ((size.width - gap * (segments - 1)) / segments).coerceAtLeast(0f)
            repeat(segments) { index ->
                drawRoundRect(if (index < shopped) c.stPickup else c.surface3,
                    Offset(index * (width + gap), 0f), Size(width, size.height), corner)
            }
        }
    }
}
