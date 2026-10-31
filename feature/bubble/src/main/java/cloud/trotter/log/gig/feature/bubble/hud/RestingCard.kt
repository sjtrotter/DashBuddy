package cloud.trotter.log.gig.feature.bubble.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cloud.trotter.log.gig.core.designsystem.theme.AppTheme
import cloud.trotter.log.gig.core.designsystem.time.rememberNow
import cloud.trotter.log.gig.core.designsystem.time.rememberTimeFormatter
import cloud.trotter.log.gig.feature.bubble.R

@Composable
fun RestingCard(model: RestingModel?) {
    val c = AppTheme.colors
    Column(Modifier.fillMaxWidth().heightIn(min = 196.dp).background(c.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model == null) {
            Text(stringResource(R.string.hud_first_run_title), style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(stringResource(R.string.hud_first_run_body), style = MaterialTheme.typography.bodyMedium, color = c.text2)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.hud_rest_last_dash), style = MaterialTheme.typography.titleLarge, color = c.text)
                model.endedAt?.let { ended ->
                    val now = rememberNow()
                    val minutes by remember(ended) { derivedStateOf { (now.value - ended).coerceAtLeast(0) / 60_000 } }
                    val hm = HoursMinutes.of(minutes * 60_000)
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.hud_rest_ended), style = MaterialTheme.typography.labelSmall, color = c.text3)
                        if (hm.hours > 0) Text("${hm.hours} ${stringResource(R.string.hud_unit_hr)}", style = AppTheme.num.xsNum, color = c.text3)
                        Text(hm.minutes.toString(), style = AppTheme.num.xsNum, color = c.text3)
                        Text(stringResource(R.string.hud_rest_ago, stringResource(R.string.hud_unit_min)),
                            style = MaterialTheme.typography.labelSmall, color = c.text3)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.earnedPerHour, style = AppTheme.num.stateNum, color = c.text, modifier = Modifier.alignByBaseline())
                Text(stringResource(R.string.hud_rest_earned_hr), style = MaterialTheme.typography.labelSmall, color = c.text2,
                    modifier = Modifier.alignByBaseline())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.deliveries.toString(), style = AppTheme.num.mdNum, color = c.text)
                Text(pluralStringResource(R.plurals.hud_deliveries, model.deliveries), color = c.text2)
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.hud_rest_accepted, model.offersAccepted, model.offersReceived), color = c.text)
                Text(stringResource(R.string.hud_rest_accepted_label), color = c.text2)
            }
            val time = rememberTimeFormatter()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(time(model.startedAt), color = c.text3, style = MaterialTheme.typography.labelSmall)
                model.endedAt?.let {
                    Text(stringResource(R.string.hud_rest_span_to), color = c.text3, style = MaterialTheme.typography.labelSmall)
                    Text(time(it), color = c.text3, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
