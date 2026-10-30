package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.WhereToVote
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.log.gig.feature.bubble.R

@Composable
internal fun TimeTrack(model: TrackModel, lateMinutes: Int?, phase: Color) {
    val c = AppTheme.colors
    val time = rememberTimeFormatter()
    val deadlineAt = model.markAt ?: model.endAt
    val description = when {
        deadlineAt == null -> stringResource(R.string.hud_track_cd_open)
        lateMinutes != null -> stringResource(R.string.hud_track_cd_late, time(deadlineAt), lateMinutes)
        else -> stringResource(R.string.hud_track_cd_due, time(deadlineAt))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp).height(24.dp).testTag("state.track")
        .semantics {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(model.fill, 0f..1f)
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 12.dp.toPx()
            val span = (size.width - 2 * inset).coerceAtLeast(0f)
            val y = size.height / 2
            fun point(fraction: Float) = Offset(inset + span * fraction, y)
            drawLine(c.surface3, point(0f), point(1f), 4.dp.toPx(), StrokeCap.Round)
            val mark = model.markFraction
            if (model.late && mark != null) {
                drawLine(phase, point(0f), point(mark), 8.dp.toPx(), StrokeCap.Round)
                drawLine(c.warn, point(mark), point(1f), 8.dp.toPx(), StrokeCap.Round)
            } else if (model.fill > 0) {
                drawLine(phase, point(0f), point(model.fill), 4.dp.toPx(), StrokeCap.Round)
            }
            if (model.endAt != null) {
                drawCircle(if (model.late) c.warn else phase, 6.dp.toPx(), point(model.fill))
                drawCircle(c.surface, 6.dp.toPx(), point(model.fill), style = Stroke(2.dp.toPx()))
            }
        }
        val travel = (maxWidth - 24.dp).coerceAtLeast(0.dp)
        TrackPin(model.anchorIcon, phase, Modifier.offset(x = 0.dp).testTag("track.pin.anchor"))
        model.endIcon?.let { TrackPin(it, phase, Modifier.offset(x = travel)) }
        // Arrived after the deadline: the mark would sit on the anchor; keep one pin width clear of it.
        // A track too narrow for two pins draws no mark at all; the warn overrun still reads late.
        model.markFraction?.takeIf { travel >= 24.dp }?.let {
            TrackPin(TrackIcon.STOPWATCH, c.warn, Modifier.offset(x = maxOf(travel * it, 24.dp)).testTag("track.pin.mark"))
        }
    }
}

@Composable
private fun TrackPin(icon: TrackIcon, ink: Color, modifier: Modifier) {
    Box(modifier.size(24.dp).background(AppTheme.colors.surface, CircleShape), contentAlignment = Alignment.Center) {
        Icon(when (icon) {
            TrackIcon.NAV_PIN -> Icons.Filled.Navigation
            TrackIcon.STORE -> Icons.Filled.Store
            TrackIcon.HOUSE -> Icons.Filled.Home
            TrackIcon.DONE_PIN -> Icons.Filled.WhereToVote
            TrackIcon.STOPWATCH -> Icons.Outlined.Timer
        }, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
    }
}
