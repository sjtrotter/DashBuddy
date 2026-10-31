package cloud.trotter.log.gig.feature.bubble.hud

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
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R

@Composable
fun CompactHeroRow(model: HeroModel, onGoalClick: (() -> Unit)? = null) {
    val description = stringResource(R.string.hud_hero_cd, moneyOrEmpty(model.earnings))
    Row(Modifier.fillMaxWidth().height(48.dp).testTag("hud.hero.compact"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.weight(1f).semantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
            val parts = model.earnings?.let { Formats.moneyParts(it) }
            Text(parts?.let { it.sign + it.symbol + it.whole } ?: EMPTY_VALUE,
                style = AppTheme.num.heroNum, color = AppTheme.colors.text, modifier = Modifier.alignByBaseline())
            parts?.let { Text(it.fraction, style = AppTheme.num.lgNum, color = AppTheme.colors.text2,
                modifier = Modifier.alignByBaseline()) }
            model.cashTips?.let {
                HeroCash(it, MaterialTheme.typography.labelMedium, Modifier.padding(start = 8.dp).alignByBaseline())
            }
        }
        GoalPill(model.goal, compact = true, onClick = onGoalClick, markLoading = model.markLoading)
    }
}
