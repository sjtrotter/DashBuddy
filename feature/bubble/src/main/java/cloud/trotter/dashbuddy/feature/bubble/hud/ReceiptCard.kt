package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
internal fun ReceiptCard(model: StateCardModel.Receipt) {
    StateLine(model.store, R.string.hud_state_delivered, model.deliveredAt)
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        model.lines.forEach { ReceiptRow(it.label, it.amount) }
        ReceiptRow(stringResource(R.string.hud_state_tip), model.tip)
        HorizontalDivider(Modifier.padding(top = 6.dp, bottom = 6.dp), color = AppTheme.colors.lineStrong)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.hud_state_total), color = AppTheme.colors.text,
                style = AppTheme.num.cardLabel.copy(fontSize = AppTheme.num.cardLabel.fontSize * (15f / 14f), fontWeight = FontWeight.SemiBold), modifier = Modifier.alignByBaseline())
            Text(moneyOrEmpty(model.total), style = AppTheme.num.cardNum.copy(fontSize = AppTheme.num.cardNum.fontSize * (20f / 15f), fontWeight = FontWeight.SemiBold),
                color = AppTheme.colors.text, modifier = Modifier.alignByBaseline())
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: Double?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppTheme.colors.text2, style = AppTheme.num.cardLabel.copy(fontSize = AppTheme.num.cardLabel.fontSize * (15f / 14f), lineHeight = AppTheme.num.cardLabel.fontSize * (26f / 14f)),
            modifier = Modifier.alignByBaseline())
        Text(moneyOrEmpty(value), color = AppTheme.colors.text,
            style = AppTheme.num.cardNum.copy(fontSize = AppTheme.num.cardNum.fontSize * (16f / 15f), lineHeight = AppTheme.num.cardNum.fontSize * (26f / 15f)),
            modifier = Modifier.alignByBaseline())
    }
}
