package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.theme.glanceSp
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun StateCard(model: StateCardModel?, kept: Double?) {
    if (model == null) return
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        when (model) {
            is StateCardModel.Waiting -> WaitingCard(model, kept)
            is StateCardModel.TaskCard -> TaskCard(model)
            is StateCardModel.Shopping -> ShoppingCard(model)
            is StateCardModel.Receipt -> ReceiptCard(model)
        }
    }
}

@Composable
internal fun StateLine(store: String, label: Int?, at: Long?) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(store, Modifier.weight(1f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = AppTheme.num.stateWord)
        if (label != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                StateLabel(stringResource(label))
                StateNumber(at?.let(rememberTimeFormatter()) ?: EMPTY_VALUE, c.text2, size = 16)
            }
        }
    }
}

@Composable
internal fun StateFigure(headline: Headline, label: Int? = null, big: Boolean = false) {
    val figureStyle = if (big) AppTheme.num.stateBigNum else AppTheme.num.stateNum
    Row(Modifier.testTag("state.figure"), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(headlineText(headline), color = AppTheme.colors.text, style = figureStyle.copy(lineHeight = glanceSp(if (big) 58 else 52)),
            modifier = Modifier.alignByBaseline())
        label?.let {
            Text(stringResource(it), color = AppTheme.colors.text2, style = AppTheme.num.stateUnit,
                modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
internal fun JobColumn(facts: JobFacts) {
    Column(Modifier.testTag("state.jobColumn").padding(bottom = 4.dp), horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(moneyOrEmpty(facts.pay), style = AppTheme.num.lgNum.copy(fontWeight = FontWeight.Medium), color = AppTheme.colors.text)
            StateLabel(stringResource(R.string.hud_state_job), size = 13)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            StateNumber(facts.miles?.let { Formats.decimal(it) } ?: EMPTY_VALUE)
            StateLabel(stringResource(R.string.hud_state_mi), size = 13)
        }
    }
}

@Composable
internal fun StateLabel(text: String, color: Color = AppTheme.colors.text3, size: Int = 14) {
    Text(text, color = color, style = AppTheme.num.cardLabel.copy(fontSize = glanceSp(size)))
}

@Composable
internal fun StateNumber(text: String, color: Color = AppTheme.colors.text2, size: Int = 15) {
    Text(text, color = color, style = AppTheme.num.cardNum.copy(fontSize = glanceSp(size)))
}

@Composable
internal fun LateFoot(minutes: Int) {
    Row(Modifier.testTag("state.foot.late"), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StateNumber(minutes.toString(), AppTheme.colors.warn)
        StateLabel(stringResource(R.string.hud_state_min_late), AppTheme.colors.warn)
    }
}
