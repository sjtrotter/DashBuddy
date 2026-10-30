package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.feature.bubble.R

@Composable
fun TopRow(model: TopRowModel) {
    val c = AppTheme.colors
    val ink = if (model.dimmed) c.text2 else c.text
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
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
    if (LocalHudTier.current >= FontScaleTier.LABELS_BESIDE) {
        Row(modifier.testTag(tag).heightIn(min = 52.dp),
            horizontalArrangement = when (alignment) {
                Alignment.Start -> Arrangement.Start
                Alignment.End -> Arrangement.End
                else -> Arrangement.Center
            }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.alignByBaseline()) { value() }
            Text(label, Modifier.padding(start = 4.dp).weight(1f, fill = false).alignByBaseline(),
                style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.neutral,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Column(modifier.testTag(tag), horizontalAlignment = alignment) {
            value()
            Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.neutral)
        }
    }
}

@Composable
private fun TopNumber(text: String, color: Color) {
    TopNumber(AnnotatedString(text), color)
}

@Composable
private fun TopNumber(text: AnnotatedString, color: Color) {
    val maxSize = AppTheme.num.xlNum.fontSize
    Text(text, style = AppTheme.num.xlNum.copy(fontWeight = FontWeight.Medium), color = color,
        maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = maxSize * 0.4f, maxFontSize = maxSize))
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
        val hr = stringResource(R.string.hud_unit_hr)
        val min = stringResource(R.string.hud_unit_min)
        // Relative unit spans shrink with the whole figure, so the duration fits as one line.
        val unit = SpanStyle(fontSize = 0.45.em, fontFamily = MaterialTheme.typography.labelSmall.fontFamily)
        TopNumber(buildAnnotatedString {
            if (hm.hours > 0) {
                append(hm.hours.toString())
                withStyle(unit) { append(" $hr ") }
            }
            append(hm.minutes.toString())
            withStyle(unit) { append(" $min") }
        }, ink)
    }
}
