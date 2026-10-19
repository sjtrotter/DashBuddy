package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.formatters.color

@Composable
internal fun LogJobCard(model: JobCardModel, onNow: () -> Unit, onUndo: (UndoTarget) -> Unit) {
    val c = AppTheme.colors
    val rule = model.lines.filterIsInstance<JobLine.Now>().lastOrNull()?.phase?.color?.color(c) ?: c.surface3
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("hud.log.${model.key}")
        .clip(RoundedCornerShape(14.dp)).background(c.surface)
        .drawBehind { drawRect(rule, size = Size(4.dp.toPx(), size.height)) }
        .padding(start = 14.dp, top = 10.dp, end = 12.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.store, Modifier.weight(1f), color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            model.score?.let { score ->
                val ink = when (model.scoreTone) {
                    ScoreTone.GOOD -> c.good; ScoreTone.WARN -> c.warn; ScoreTone.BAD -> c.bad; ScoreTone.NEUTRAL -> c.neutral
                }
                val description = stringResource(R.string.hud_log_score_cd, score)
                Text(score.toString(), Modifier.background(ink.copy(alpha = .14f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp).semantics { contentDescription = description },
                    style = AppTheme.num.smNum, color = ink)
            }
            LogQuote(model.pay, model.miles, c.text)
        }
        model.lines.forEach { line ->
            if (line is JobLine.Now) LogNowLine(line, onNow)
            else LogJobLine(line, onUndo)
        }
    }
}

@Composable
private fun LogJobLine(line: JobLine, onUndo: (UndoTarget) -> Unit) {
    val time = rememberTimeFormatter()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        LogTime(time(if (line is JobLine.PickedUp) line.arrivedAt ?: line.at else line.at))
        Column(Modifier.weight(1f)) {
            when (line) {
                is JobLine.Accepted -> {
                    val label = stringResource(if (line.addOn) R.string.hud_log_addon else R.string.hud_log_accepted)
                    val hour = line.perHour?.let { Formats.money0(it) }
                    val mile = line.perMile?.let { Formats.money(it) }
                    val rates = listOfNotNull(hour?.let { logTextParts(R.string.hud_log_per_hr, logFigure(it)) },
                        mile?.let { logTextParts(R.string.hud_log_per_mi, logFigure(it)) })
                        .flatMapIndexed { index, parts -> if (index == 0) parts else listOf(LogTextPart(" ")) + parts }
                    LogText(if (rates.isEmpty()) listOf(LogTextPart(label))
                        else logTextParts(R.string.hud_log_accepted_rates, listOf(LogTextPart(label)), rates))
                }
                is JobLine.PickedUp -> {
                    val at = time(line.pickedUpAt)
                    LogText(logTextParts(if (line.arrivedAt != null) R.string.hud_log_picked_up_arrived else R.string.hud_log_picked_up, logFigure(at)))
                }
                is JobLine.Delivered -> LogText(logTextParts(R.string.hud_log_delivered))
                is JobLine.Unassigned -> LogText(logTextParts(R.string.hud_log_unassigned))
                is JobLine.CashTip -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogText(logTextParts(R.string.hud_log_cash_tip, logFigure(Formats.money(line.amount))))
                    if (line.pending) Text(stringResource(R.string.hud_log_pending), color = AppTheme.colors.warn, fontSize = 11.sp)
                }
                is JobLine.Now -> Unit
            }
        }
        if (line is JobLine.PickedUp) line.dwellMs?.let { dwell ->
            val minutes = stringResource(R.string.hud_log_minutes, dwell / 60_000)
            LogText(logTextParts(R.string.hud_log_at_store, logFigure(minutes)))
        }
        if (line is JobLine.Delivered) line.paid?.let {
            LogText(logTextParts(R.string.hud_log_paid, logFigure(Formats.money(it))))
        }
        if (line is JobLine.CashTip) line.undo?.let { window ->
            val now by rememberNow()
            if (now < window.closesAt) {
                val description = stringResource(R.string.hud_log_cd_undo, Formats.money(window.amount))
                Text(stringResource(R.string.hud_log_undo), color = AppTheme.colors.accent, fontSize = 13.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("hud.log.undo")
                        .semantics { contentDescription = description }.clickable(role = Role.Button) { onUndo(window.target) }
                        .padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
internal fun LogTime(text: String, color: Color = AppTheme.colors.neutral) {
    Text(text, Modifier.width(58.dp), style = AppTheme.num.smNum.copy(fontSize = 12.sp), color = color)
}

internal data class LogTextPart(val text: String, val number: Boolean = false)

internal fun logFigure(text: String) = listOf(LogTextPart(text, number = true))

// Read placeholders from the unformatted resource, so literal labels/units and figures stay separate.
// Nested parts (such as accepted rates) retain their styles, including in reordered translations.
private val logPlaceholder = Regex("%([1-9][0-9]*)\\$[sd]")

internal fun logTextParts(template: String, vararg arguments: List<LogTextPart>): List<LogTextPart> = buildList {
    var end = 0
    logPlaceholder.findAll(template).forEach { match ->
        if (match.range.first > end) add(LogTextPart(template.substring(end, match.range.first)))
        addAll(arguments[match.groupValues[1].toInt() - 1])
        end = match.range.last + 1
    }
    if (end < template.length) add(LogTextPart(template.substring(end)))
}

@Composable
internal fun logTextParts(@StringRes template: Int, vararg arguments: List<LogTextPart>): List<LogTextPart> =
    logTextParts(stringResource(template), *arguments)

internal fun buildLogText(parts: List<LogTextPart>, numberStyle: SpanStyle): AnnotatedString =
    AnnotatedString.Builder().apply {
        parts.forEach { part ->
            if (part.number) pushStyle(numberStyle)
            append(part.text)
            if (part.number) pop()
        }
    }.toAnnotatedString()

@Composable
internal fun LogText(parts: List<LogTextPart>, color: Color = AppTheme.colors.text2) {
    val numberStyle = SpanStyle(fontFamily = AppTheme.num.smNum.fontFamily, fontFeatureSettings = AppTheme.num.smNum.fontFeatureSettings, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.text)
    Text(buildLogText(parts, numberStyle), color = color, fontSize = 13.sp)
}

@Composable
internal fun LogQuote(pay: Double?, miles: Double?, color: Color, dimmed: Boolean = false) {
    Column(horizontalAlignment = Alignment.End) {
        Text(moneyOrEmpty(pay), style = AppTheme.num.mdNum.copy(fontWeight = if (dimmed) FontWeight.Medium else FontWeight.Bold), color = color)
        miles?.let { Text(stringResource(R.string.hud_log_miles, Formats.decimal(it)), style = AppTheme.num.smNum, color = AppTheme.colors.neutral) }
    }
}
