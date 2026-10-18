package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun HeroRow(model: HeroModel) {
    Row(Modifier.fillMaxWidth().height(84.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).testTag("hud.hero")) {
            val parts = model.profit?.let { Formats.moneyParts(it) }
            Row(verticalAlignment = Alignment.Bottom) {
                if (parts == null) {
                    Text(EMPTY_VALUE, style = AppTheme.num.displayNum, color = AppTheme.colors.text)
                } else {
                    Text(parts.sign + parts.symbol, style = AppTheme.num.heroUnitNum, color = AppTheme.colors.text2,
                        modifier = Modifier.alignByBaseline())
                    Text(parts.whole, style = AppTheme.num.displayNum, color = AppTheme.colors.text,
                        modifier = Modifier.alignByBaseline())
                    Text(parts.fraction, style = AppTheme.num.heroUnitNum, color = AppTheme.colors.text2,
                        modifier = Modifier.alignByBaseline())
                }
            }
            Text(stringResource(R.string.hud_hero_profit), style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.text2)
        }
        GoalPill(model.goal)
    }
}
