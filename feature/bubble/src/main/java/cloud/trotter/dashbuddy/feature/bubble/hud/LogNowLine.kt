package cloud.trotter.dashbuddy.feature.bubble.hud

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
import cloud.trotter.dashbuddy.core.designsystem.theme.AppTheme
import cloud.trotter.dashbuddy.core.designsystem.time.rememberNow
import cloud.trotter.dashbuddy.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.feature.bubble.R
import cloud.trotter.dashbuddy.feature.bubble.formatters.color

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
    val text = when (line.phase) {
        NowPhase.PICKUP_NAV -> if (due == null) stringResource(R.string.hud_log_now_nodue_to_store, figure)
            else stringResource(R.string.hud_log_now_to_store, due)
        NowPhase.DROPOFF_NAV -> if (due == null) stringResource(R.string.hud_log_now_nodue_to_door, figure)
            else stringResource(R.string.hud_log_now_to_door, due)
        NowPhase.PICKUP_ARRIVED -> if (due == null) stringResource(R.string.hud_log_now_nodue_at_store, figure)
            else stringResource(R.string.hud_log_now_at_store, figure, due)
        NowPhase.DROPOFF_ARRIVED -> if (due == null) stringResource(R.string.hud_log_now_nodue_at_door, figure)
            else stringResource(R.string.hud_log_now_at_door, figure, due)
        NowPhase.SHOPPING -> stringResource(R.string.hud_log_now_shopping, line.shopped ?: 0, line.total ?: 0)
        NowPhase.RECEIPT -> stringResource(R.string.hud_log_now_receipt)
    }
    Row(Modifier.fillMaxWidth().testTag("hud.log.now").clickable(role = Role.Button, onClick = onNow)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogTime(stringResource(R.string.hud_log_now), line.phase.color.color(AppTheme.colors))
        Column(Modifier.weight(1f)) {
            if (!parked && due != null && line.phase != NowPhase.RECEIPT) LogText(
                stringResource(if (StateHeadline.lateMinutes(line.dueAt, now) != null) R.string.hud_log_en_route else R.string.hud_log_left, figure), listOf(figure))
            LogText(text, listOfNotNull(figure, due, line.shopped?.toString(), line.total?.toString()), AppTheme.colors.text)
        }
        line.net?.let { LogText(stringResource(R.string.hud_log_net, Formats.money(it)), listOf(Formats.money(it))) }
    }
}
