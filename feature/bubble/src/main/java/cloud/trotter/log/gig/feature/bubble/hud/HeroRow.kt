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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R

@Composable
fun HeroRow(model: HeroModel, onGoalCelebrated: (String) -> Unit = {}, onGoalClick: (() -> Unit)? = null) {
    val compact = LocalHudTier.current >= FontScaleTier.COMPACT_HERO
    val figureStyle = if (compact) AppTheme.num.heroNum else AppTheme.num.displayNum
    val unitStyle = if (compact) AppTheme.num.lgNum else AppTheme.num.heroUnitNum
    Row(Modifier.fillMaxWidth().heightIn(min = 84.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).testTag(if (compact) "hud.hero.compact" else "hud.hero")) {
            val parts = model.earnings?.let { Formats.moneyParts(it) }
            // #1443: the figure is unlabelled on screen; TalkBack reads it as one "Earned $X".
            val description = stringResource(R.string.hud_hero_cd, moneyOrEmpty(model.earnings))
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = description }, verticalAlignment = Alignment.Bottom) {
                if (parts == null) {
                    Text(EMPTY_VALUE, style = figureStyle, color = AppTheme.colors.text)
                } else {
                    Text(parts.sign + parts.symbol, style = unitStyle, color = AppTheme.colors.text2,
                        modifier = Modifier.alignByBaseline())
                    Text(parts.whole, style = figureStyle, color = AppTheme.colors.text,
                        modifier = Modifier.alignByBaseline())
                    Text(parts.fraction, style = unitStyle, color = AppTheme.colors.text2,
                        modifier = Modifier.alignByBaseline())
                }
            }
            model.cashTips?.let { HeroCash(it, MaterialTheme.typography.labelSmall) }
        }
        GoalPill(model.goal, celebrateKey = model.celebrateKey, onCelebrated = onGoalCelebrated, onClick = onGoalClick, markLoading = model.markLoading)
    }
}

/** The displayed shift's cash tips, apart from its earnings; the caller passes only an amount above zero. */
@Composable
internal fun HeroCash(amount: Double, style: TextStyle, modifier: Modifier = Modifier) {
    Text(stringResource(R.string.hud_hero_cash, Formats.money(amount)), modifier.testTag("hud.hero.cash"),
        style = style, color = AppTheme.colors.text2)
}
