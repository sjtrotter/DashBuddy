package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R

@Composable
fun CompactHeroRow(model: HeroModel) {
    val description = stringResource(R.string.hud_hero_compact_cd, moneyOrEmpty(model.profit))
    Row(Modifier.fillMaxWidth().height(48.dp).testTag("hud.hero.compact"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f).semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
            val parts = model.profit?.let { Formats.moneyParts(it) }
            Text(parts?.let { it.sign + it.symbol + it.whole } ?: EMPTY_VALUE,
                style = AppTheme.num.heroNum, color = AppTheme.colors.text, modifier = Modifier.alignByBaseline())
            parts?.let { Text(it.fraction, style = AppTheme.num.lgNum, color = AppTheme.colors.text2,
                modifier = Modifier.alignByBaseline()) }
            Text(stringResource(R.string.hud_hero_profit), style = MaterialTheme.typography.labelMedium,
                color = AppTheme.colors.text2, modifier = Modifier.padding(start = 8.dp).alignByBaseline())
        }
        GoalPill(model.goal, compact = true)
    }
}
