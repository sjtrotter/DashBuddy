package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun TopRow(model: TopRowModel) {
    val c = AppTheme.colors
    val ink = if (model.dimmed) c.text2 else c.text
    Row(Modifier.fillMaxWidth().height(52.dp)) {
        TopMetric(stringResource(R.string.hud_top_earned), "hud.top.earned", Modifier.weight(1f), Alignment.Start) {
            TopNumber(model.earned, ink)
        }
        TopMetric(stringResource(R.string.hud_top_online), "hud.top.online", Modifier.weight(1f), Alignment.CenterHorizontally) {
            OnlineValue(model.online, ink)
        }
        TopMetric(stringResource(R.string.hud_top_miles), "hud.top.miles", Modifier.weight(1f), Alignment.End) {
            TopNumber(model.miles, ink)
        }
    }
}

@Composable
private fun TopMetric(label: String, tag: String, modifier: Modifier, alignment: Alignment.Horizontal, value: @Composable () -> Unit) {
    Column(modifier.testTag(tag), horizontalAlignment = alignment) {
        value()
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.neutral)
    }
}

@Composable
private fun TopNumber(text: String, color: Color) {
    Text(text, style = AppTheme.num.xlNum.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1)
}

@Composable
private fun OnlineValue(span: OnlineSpan, ink: Color) {
    val duration = when (span) {
        is OnlineSpan.Ticking -> {
            val now = rememberNow()
            val minutes by remember(span) { derivedStateOf { (now.value - span.startedAt).coerceAtLeast(0) / 60_000 } }
            minutes * 60_000
        }
        is OnlineSpan.Fixed -> span.ms
        OnlineSpan.Unknown -> null
    }
    if (duration == null) {
        TopNumber(EMPTY_VALUE, ink)
    } else {
        val hm = HoursMinutes.of(duration)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (hm.hours > 0) {
                TopNumber(hm.hours.toString(), ink)
                Text(stringResource(R.string.hud_unit_hr), style = MaterialTheme.typography.labelSmall, color = ink)
            }
            TopNumber(hm.minutes.toString(), ink)
            Text(stringResource(R.string.hud_unit_min), style = MaterialTheme.typography.labelSmall, color = ink)
        }
    }
}
