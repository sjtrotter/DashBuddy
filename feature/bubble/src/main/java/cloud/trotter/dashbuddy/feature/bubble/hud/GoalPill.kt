package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

/** Static B2 states. The goal sheet and one-time met sequence belong to S3 and S2e. */
@Composable
fun GoalPill(state: GoalPillState) {
    val c = AppTheme.colors
    val met = state == GoalPillState.Met
    val description = when (state) {
        GoalPillState.NoGoal -> stringResource(R.string.hud_goal_set)
        GoalPillState.Met -> stringResource(R.string.hud_goal_cd_met)
        is GoalPillState.InProgress -> stringResource(R.string.hud_goal_cd_progress, state.remaining)
    }
    Row(Modifier.testTag("hud.goal").semantics { contentDescription = description }
        .height(56.dp).background(c.surface2, RoundedCornerShape(28.dp))
        .padding(start = if (met) 6.dp else 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (met) 0.dp else 10.dp)) {
        when (state) {
            GoalPillState.NoGoal -> Text(stringResource(R.string.hud_goal_set), style = MaterialTheme.typography.labelMedium, color = c.text2)
            is GoalPillState.InProgress -> Column {
                Text(state.remaining, style = AppTheme.num.lgNum, color = c.text)
                Text(stringResource(R.string.hud_goal_to_go), style = MaterialTheme.typography.labelSmall, color = c.text2)
            }
            GoalPillState.Met -> Unit
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val inset = (size.width - 38.dp.toPx()) / 2
                val arcSize = Size(38.dp.toPx(), 38.dp.toPx())
                drawCircle(c.surface3, radius = 19.dp.toPx(), style = Stroke(stroke))
                val progress = if (met) 1f else (state as? GoalPillState.InProgress)?.progress ?: 0f
                drawArc(if (met) c.accent else c.good, -90f, 360f * progress, false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
            }
            Icon(if (met) Icons.Filled.Flag else Icons.Outlined.Flag, null,
                tint = if (met) c.accent else c.text2, modifier = Modifier.size(22.dp))
        }
    }
}
