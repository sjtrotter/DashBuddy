package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.log.gig.domain.format.Formats
import cloud.trotter.log.gig.feature.bubble.R
import cloud.trotter.log.gig.feature.bubble.formatters.color

@Composable
internal fun LogNowLine(line: JobLine.Now, onNow: () -> Unit) {
    val now by rememberNow()
    val parked = line.phase == NowPhase.PICKUP_ARRIVED || line.phase == NowPhase.DROPOFF_ARRIVED || line.phase == NowPhase.SHOPPING
    val headline = if (parked) StateHeadline.parked(line.anchorAt, line.dueAt, now)
        else StateHeadline.moving(line.anchorAt, line.dueAt, now)
    val figure = when (headline) {
        is Headline.Minutes -> stringResource(R.string.hud_log_minutes, headline.n.toLong())
        else -> headlineText(headline)
    }
    val due = line.dueAt?.let(rememberTimeFormatter())
    val figureParts = logFigure(figure)
    val dueParts = due?.let(::logFigure)
    val parts = when (line.phase) {
        NowPhase.PICKUP_NAV -> if (dueParts == null) logTextParts(R.string.hud_log_now_nodue_to_store, figureParts)
            else logTextParts(R.string.hud_log_now_to_store, dueParts)
        NowPhase.DROPOFF_NAV -> if (dueParts == null) logTextParts(R.string.hud_log_now_nodue_to_door, figureParts)
            else logTextParts(R.string.hud_log_now_to_door, dueParts)
        NowPhase.PICKUP_ARRIVED -> if (dueParts == null) logTextParts(R.string.hud_log_now_nodue_at_store, figureParts)
            else logTextParts(R.string.hud_log_now_at_store, figureParts, dueParts)
        NowPhase.DROPOFF_ARRIVED -> if (dueParts == null) logTextParts(R.string.hud_log_now_nodue_at_door, figureParts)
            else logTextParts(R.string.hud_log_now_at_door, figureParts, dueParts)
        NowPhase.SHOPPING -> logTextParts(R.string.hud_log_now_shopping,
            logFigure((line.shopped ?: 0).toString()), logFigure((line.total ?: 0).toString()))
        NowPhase.RECEIPT -> logTextParts(R.string.hud_log_now_receipt)
    }
    Row(Modifier.fillMaxWidth().testTag("hud.log.now").clickable(role = Role.Button, onClick = onNow)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogTime(stringResource(R.string.hud_log_now), line.phase.color.color(AppTheme.colors))
        Column(Modifier.weight(1f)) {
            if (!parked && due != null && line.phase != NowPhase.RECEIPT) LogText(
                logTextParts(if (StateHeadline.lateMinutes(line.dueAt, now) != null) R.string.hud_log_en_route else R.string.hud_log_left, figureParts))
            LogText(parts, AppTheme.colors.text)
        }
        line.net?.let { LogText(logTextParts(R.string.hud_log_net, logFigure(Formats.money(it)))) }
    }
}
