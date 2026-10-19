package cloud.trotter.dashbuddy.feature.bubble.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.feature.bubble.R

internal val GoalMetPhaseKey = SemanticsPropertyKey<GoalMetPhase>("GoalMetPhase")
internal val GoalMetFrameKey = SemanticsPropertyKey<GoalMetFrame>("GoalMetFrame")
private data class GoalRun(val key: String, val previous: GoalPillState.InProgress?)

@Composable
fun GoalPill(
    state: GoalPillState,
    compact: Boolean = false,
    celebrateKey: String? = null,
    onCelebrated: (String) -> Unit = {},
    onClick: (() -> Unit)? = null,
    markLoading: Boolean = false,
) {
    val c = AppTheme.colors
    val met = state == GoalPillState.Met
    val reduced = rememberReducedMotion()
    var previous by remember { mutableStateOf<GoalPillState.InProgress?>(null) }
    var latched by remember { mutableStateOf<GoalRun?>(null) }
    var consumed by remember { mutableStateOf<String?>(null) }
    SideEffect {
        if (state is GoalPillState.InProgress && state.remaining != EMPTY_VALUE) previous = state
    }
    // A persisted mark may land during the run. Retain the run independently of celebrateKey.
    val running = if (!met || compact) null else {
        celebrateKey?.takeIf { it != consumed }?.let { GoalRun(it, previous) } ?: latched
    }
    val elapsed = remember(running?.key) { Animatable(if (running?.previous == null) GoalMetSequence.COLLAPSE_MS.toFloat() else 0f) }
    val celebrated by rememberUpdatedState(onCelebrated)
    LaunchedEffect(running?.key) {
        if (running == null) {
            latched = null
        } else {
            latched = running
            consumed = running.key
            celebrated(running.key) // Persist before the first animated frame, including reduced motion.
            if (!reduced) elapsed.animateTo(GoalMetSequence.DURATION_MS.toFloat(),
                tween(GoalMetSequence.DURATION_MS - elapsed.value.toInt(), easing = LinearEasing))
        }
    }
    val frame = when {
        !met -> null
        running == null && markLoading -> GoalMetFrame(
            GoalMetPhase.CLOSE, 0f, 1f, previous?.progress ?: 1f, 0f, GoalIcon.OUTLINED_FLAG)
        else -> GoalMetSequence.frameAt(
            if (running == null || reduced) GoalMetSequence.DURATION_MS.toLong() else elapsed.value.toLong(),
            running?.previous?.progress ?: 1f,
        )
    }
    val text = if (met) running?.previous?.takeIf { frame?.textAlpha != 0f } else state
    val description = when (state) {
        GoalPillState.NoGoal -> stringResource(R.string.hud_goal_set)
        GoalPillState.Met -> stringResource(R.string.hud_goal_cd_met)
        is GoalPillState.InProgress -> stringResource(R.string.hud_goal_to_go, state.remaining)
    }
    val clickLabel = stringResource(R.string.hud_goal_click_label)
    val expanded = text != null && text != GoalPillState.Met
    val collapse = frame?.collapse ?: 0f
    Row(Modifier.testTag("hud.goal").semantics {
        contentDescription = description
        if (met) liveRegion = LiveRegionMode.Polite
        frame?.let {
            this[GoalMetPhaseKey] = it.phase
            this[GoalMetFrameKey] = it
        }
    }.then(if (onClick != null) Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick) else Modifier)
        .height(if (compact) 44.dp else 56.dp)
        .background(c.surface2, RoundedCornerShape(28.dp))
        .padding(start = if (expanded) (14 - 8 * collapse).dp else 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (expanded) {
            // The full text is measured while its visible width shrinks toward the ring.
            Box(Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0))
                layout((placeable.width * (1f - collapse)).toInt(), placeable.height) {
                    placeable.placeRelative(0, 0)
                }
            }.alpha(frame?.textAlpha ?: 1f).then(if (met) Modifier.clearAndSetSemantics {} else Modifier)) {
                when (text) {
                    GoalPillState.NoGoal -> Text(stringResource(R.string.hud_goal_set),
                        style = MaterialTheme.typography.labelMedium, color = c.text2)
                    is GoalPillState.InProgress -> Column {
                        Text(text.remaining, style = if (compact) AppTheme.num.mdNum else AppTheme.num.lgNum, color = c.text)
                        Text(stringResource(R.string.hud_goal_to_go_label), style = MaterialTheme.typography.labelSmall, color = c.text2)
                    }
                }
            }
            Spacer(Modifier.width((10 * (1f - collapse)).dp))
        }
        Box(Modifier.size(if (compact) 36.dp else 44.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                val diameter = (if (compact) 30.dp else 38.dp).toPx()
                val inset = (size.width - diameter) / 2
                drawCircle(c.surface3, radius = diameter / 2, style = Stroke(stroke))
                val progress = frame?.arcSweep ?: (state as? GoalPillState.InProgress)?.progress ?: 0f
                drawArc(lerp(c.good, c.accent, frame?.arcAccent ?: 0f), -90f, 360f * progress, false,
                    topLeft = Offset(inset, inset), size = Size(diameter, diameter), style = Stroke(stroke))
            }
            Icon(when (frame?.icon) {
                GoalIcon.CHECK -> Icons.Filled.Check
                GoalIcon.FILLED_FLAG -> Icons.Filled.Flag
                else -> Icons.Outlined.Flag
            }, null, tint = if (met) lerp(c.text2, c.accent, frame?.arcAccent ?: 1f) else c.text2,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp))
        }
    }
}
