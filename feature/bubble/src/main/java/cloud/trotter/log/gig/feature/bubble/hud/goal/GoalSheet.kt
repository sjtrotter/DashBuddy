package cloud.trotter.log.gig.feature.bubble.hud.goal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.component.AppChip
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.feature.bubble.hud.GoalPillMapper
import cloud.trotter.log.gig.feature.bubble.hud.GoalPillState

@Composable
fun GoalSheet(
    model: GoalSheetModel,
    onSet: (Int) -> Unit,
    onClear: () -> Unit,
    onPreset: (Int) -> Unit,
) {
    val c = AppTheme.colors
    val goal = model.goalDollars
    val progress = GoalPillMapper.map(goal?.toDouble(), model.todayEarned)
    val clearEnabled = goal != null && !model.busy
    Column(Modifier.fillMaxSize().testTag("goal.sheet").padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hud_goal_title), style = MaterialTheme.typography.titleMedium, color = c.text)
            Row(Modifier.testTag("goal.clear").heightIn(min = 44.dp).clip(CircleShape)
                .clickable(enabled = clearEnabled, role = Role.Button, onClick = onClear)
                .padding(vertical = 7.dp).alpha(if (clearEnabled) 1f else .38f)
                .border(1.dp, c.bad, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Delete, null, tint = c.bad, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.hud_goal_clear), color = c.bad, style = MaterialTheme.typography.labelMedium)
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Keep progress visible before assigning the remaining height to the controls.
            val controlsHeight = (maxHeight - 56.dp).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().heightIn(max = controlsHeight)) {
                    Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            StepButton("goal.minus", stringResource(R.string.hud_goal_minus), "−",
                                !model.busy && GoalBounds.canDecrease(goal)) { GoalBounds.minus(goal)?.let(onSet) }
                            Text(goal?.let { Formats.money0(it.toDouble()) } ?: EMPTY_VALUE,
                                style = AppTheme.num.stateNum, color = c.text, modifier = Modifier.testTag("goal.figure"))
                            StepButton("goal.plus", stringResource(R.string.hud_goal_plus), "+",
                                !model.busy && GoalBounds.canIncrease(goal)) { onSet(GoalBounds.plus(goal)) }
                        }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (preset in listOf(100, 125, 150, 175, 200)) {
                                val selected = goal == preset
                                Box(Modifier.heightIn(min = 44.dp).testTag("goal.preset.$preset")
                                    .semantics { this.selected = selected }.clip(CircleShape)
                                    .clickable(enabled = !model.busy, role = Role.Button) { onPreset(preset) },
                                    contentAlignment = Alignment.Center) {
                                    AppChip(Formats.money0(preset.toDouble()),
                                        color = if (selected) c.accentText else c.text,
                                        container = if (selected) c.accent else c.surface3)
                                }
                            }
                        }
                        if (model.failed) Text(stringResource(R.string.hud_goal_failed),
                            color = c.warn, style = MaterialTheme.typography.labelSmall)
                    }
                }
                // The progress block owns spare height and stays visible while controls scroll.
                Column(Modifier.fillMaxWidth().weight(1f).heightIn(min = 56.dp).testTag("goal.progress").padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.Bottom) {
                    if (goal != null) {
                        val fill = when (progress) {
                            GoalPillState.Met -> 1f
                            is GoalPillState.InProgress -> progress.progress
                            else -> 0f
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).testTag("goal.bar")
                            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fill, 0f..1f) }
                            .clip(CircleShape).background(c.surface3)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fill)
                                .background(if (progress == GoalPillState.Met) c.good else c.accent))
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.hud_goal_earned_today, model.todayEarned?.let(Formats::money) ?: EMPTY_VALUE),
                            color = c.text2, style = MaterialTheme.typography.labelSmall)
                        if (goal != null) Text(when (progress) {
                            GoalPillState.Met -> stringResource(R.string.hud_goal_met)
                            is GoalPillState.InProgress -> stringResource(R.string.hud_goal_to_go, progress.remaining)
                            else -> EMPTY_VALUE
                        }, color = c.text2, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepButton(tag: String, description: String, text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).testTag(tag).semantics { contentDescription = description }
        .clip(CircleShape).background(AppTheme.colors.surface2)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = AppTheme.num.lgNum,
            color = AppTheme.colors.text.copy(alpha = if (enabled) 1f else .38f))
    }
}
