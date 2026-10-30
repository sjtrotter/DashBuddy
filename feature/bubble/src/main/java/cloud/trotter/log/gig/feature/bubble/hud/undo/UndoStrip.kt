package cloud.trotter.log.gig.feature.bubble.hud.undo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.LocalNowProvider
import cloud.trotter.log.gig.core.designsystem.time.rememberDeadlinePassed
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R

@Composable
fun undoStripVisible(model: UndoStripModel?): Boolean {
    if (model == null) return false
    return model.busy || model.failed || !rememberDeadlinePassed(model.deadline).value
}

/** A deadline from the host, with a smooth drain driven only by Compose frames. */
@Composable
fun UndoStrip(model: UndoStripModel, onUndo: () -> Unit) {
    val clock = LocalNowProvider.current
    var remaining by remember(model) { mutableFloatStateOf(((model.deadline - clock.millis()).toFloat() / UndoStripModel.WINDOW_MS).coerceIn(0f, 1f)) }
    var tapped by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, clock) {
        val initial = (model.deadline - clock.millis()).coerceIn(0, UndoStripModel.WINDOW_MS)
        val start = withFrameNanos { it }
        while (remaining > 0f) {
            withFrameNanos { frame ->
                remaining = ((initial - (frame - start) / 1_000_000L).toFloat() / UndoStripModel.WINDOW_MS).coerceIn(0f, 1f)
            }
        }
    }
    if (!model.busy && !model.failed && rememberDeadlinePassed(model.deadline).value) return
    val where = model.where ?: model.whereFallback?.let { stringResource(it) }
    Box(Modifier.fillMaxWidth().height(48.dp).testTag("undo.strip")) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (model.failed) stringResource(R.string.hud_undo_failed) else
                if (where == null) stringResource(R.string.hud_undo_without_where,
                    stringResource(model.whatLabel), Formats.money(model.amountCents / 100.0))
                else stringResource(R.string.hud_undo_line, stringResource(model.whatLabel),
                    Formats.money(model.amountCents / 100.0), where),
                Modifier.weight(1f), color = AppTheme.colors.text2, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { if (!tapped && !model.busy) { tapped = true; onUndo() } }, enabled = !tapped && !model.busy) {
                Text(stringResource(R.string.hud_undo_action))
            }
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(remaining).height(2.dp)
            .background(AppTheme.colors.accent).testTag("undo.drain")
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(remaining, 0f..1f) })
    }
}
